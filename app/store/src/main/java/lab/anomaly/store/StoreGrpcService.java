package lab.anomaly.store;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import lab.anomaly.contracts.SeriesIds;
import lab.anomaly.contracts.v1.DaySummaryRequest;
import lab.anomaly.contracts.v1.HistoryChunk;
import lab.anomaly.contracts.v1.HistoryRequest;
import lab.anomaly.contracts.v1.PingReply;
import lab.anomaly.contracts.v1.PingRequest;
import lab.anomaly.contracts.v1.Resolution;
import lab.anomaly.contracts.v1.SeriesDaySummary;
import lab.anomaly.contracts.v1.SeriesSummary;
import lab.anomaly.contracts.v1.StoreServiceGrpc;
import lab.anomaly.contracts.v1.SummaryRequest;
import lab.anomaly.kit.settings.SettingsHolder;
import lab.anomaly.store.read.HistoryRepository;
import org.springframework.stereotype.Service;

/**
 * The Store's gRPC answers (add-store design S4). Long answers are streamed in chunks. A calendar day is just a
 * window: GetDaySummary computes midnight to midnight in the time zone (23- or 25-hour days included) and runs the
 * same query as GetSummary (option A, user decision 15 Nov 2026).
 */
@Service
class StoreGrpcService extends StoreServiceGrpc.StoreServiceImplBase {

    private final HistoryRepository history;
    private final SettingsHolder<StoreSettings> settings;
    private final lab.anomaly.store.write.RawPointRemover remover;

    StoreGrpcService(HistoryRepository history, SettingsHolder<StoreSettings> settings,
                     lab.anomaly.store.write.RawPointRemover remover) {
        this.history = history;
        this.settings = settings;
        this.remover = remover;
    }

    /** add-reporter R6: remove raw points older than a time; never the last 3 hours; a future time is refused. */
    @Override
    public void removeRawBefore(lab.anomaly.contracts.v1.RemoveRawRequest request,
                                StreamObserver<lab.anomaly.contracts.v1.RemoveRawReply> reply) {
        try {
            var result = remover.removeBefore(request.getBeforeMs(), System.currentTimeMillis());
            reply.onNext(lab.anomaly.contracts.v1.RemoveRawReply.newBuilder().setRemovedPoints(result.removedPoints())
                    .setEffectiveBeforeMs(result.effectiveBeforeMs()).build());
            reply.onCompleted();
        } catch (IllegalArgumentException e) {
            reply.onError(io.grpc.Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingReply> reply) {
        reply.onNext(PingReply.newBuilder().setService("store").setTimeMs(System.currentTimeMillis()).build());
        reply.onCompleted();
    }

    @Override
    public void getHistory(HistoryRequest request, StreamObserver<HistoryChunk> chunks) {
        String problem = rangeProblem(request.getFromMs(), request.getToMs());
        if (problem == null && !SeriesIds.isId(request.getSeriesId())) {
            problem = "series_id must be 16 hex characters";
        }
        if (problem == null && request.getResolution() == Resolution.RESOLUTION_UNSPECIFIED) {
            problem = "resolution must be RAW or MINUTE";
        }
        if (problem != null) {
            chunks.onError(Status.INVALID_ARGUMENT.withDescription(problem).asRuntimeException());
            return;
        }
        try {
            var send = (java.util.function.Consumer<java.util.List<lab.anomaly.contracts.v1.Point>>) points ->
                    chunks.onNext(HistoryChunk.newBuilder().setSeriesId(request.getSeriesId()).addAllPoints(points).build());
            if (request.getResolution() == Resolution.RESOLUTION_RAW) {
                history.raw(request.getSeriesId(), request.getFromMs(), request.getToMs(), send);
            } else {
                history.perMinute(request.getSeriesId(), request.getFromMs(), request.getToMs(), send);
            }
            chunks.onCompleted();
        } catch (RuntimeException e) {
            chunks.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getSummary(SummaryRequest request, StreamObserver<SeriesSummary> summaries) {
        String problem = rangeProblem(request.getFromMs(), request.getToMs());
        if (problem != null) {
            summaries.onError(Status.INVALID_ARGUMENT.withDescription(problem).asRuntimeException());
            return;
        }
        try {
            history.summaries(request.getFromMs(), request.getToMs(), s -> summaries.onNext(SeriesSummary.newBuilder()
                    .setSeriesId(s.seriesId()).setCount(s.count()).setMin(s.min()).setMax(s.max()).setMean(s.mean()).setP95(s.p95())
                    .setFromMs(request.getFromMs()).setToMs(request.getToMs()).build()));
            summaries.onCompleted();
        } catch (RuntimeException e) {
            summaries.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getDaySummary(DaySummaryRequest request, StreamObserver<SeriesDaySummary> summaries) {
        long from;
        long to;
        try {
            ZoneId zone = ZoneId.of(request.getTimeZone());
            LocalDate day = LocalDate.parse(request.getDate());
            from = day.atStartOfDay(zone).toInstant().toEpochMilli();
            to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        } catch (DateTimeException e) {
            summaries.onError(Status.INVALID_ARGUMENT.withDescription("date must be like 2026-11-15 and time_zone like Asia/Kolkata: "
                    + e.getMessage()).asRuntimeException());
            return;
        }
        try {
            history.summaries(from, to, s -> summaries.onNext(SeriesDaySummary.newBuilder()
                    .setSeriesId(s.seriesId()).setCount(s.count()).setMin(s.min()).setMax(s.max()).setMean(s.mean()).setP95(s.p95()).build()));
            summaries.onCompleted();
        } catch (RuntimeException e) {
            summaries.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /** add-dashboard-view D1: every line's description. */
    @Override
    public void listSeries(lab.anomaly.contracts.v1.ListSeriesRequest request,
                           StreamObserver<lab.anomaly.contracts.v1.SeriesDescriptor> out) {
        try {
            history.descriptors(bytes -> {
                try {
                    out.onNext(lab.anomaly.contracts.v1.SeriesDescriptor.parseFrom(bytes));
                } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                    // a description that cannot be read is skipped; the next one is sent
                }
            });
            out.onCompleted();
        } catch (RuntimeException e) {
            out.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /** add-dashboard-view D1: many lines, one range; at most 1000 lines. */
    @Override
    public void getHistoryBatch(lab.anomaly.contracts.v1.HistoryBatchRequest request, StreamObserver<HistoryChunk> chunks) {
        String problem = rangeProblem(request.getFromMs(), request.getToMs());
        if (problem == null && request.getSeriesIdsCount() > 1000) {
            problem = "at most 1000 lines per question (asked for " + request.getSeriesIdsCount() + ")";
        }
        if (problem == null && request.getSeriesIdsList().stream().anyMatch(id -> !SeriesIds.isId(id))) {
            problem = "every series_id must be 16 hex characters";
        }
        if (problem == null && request.getResolution() == Resolution.RESOLUTION_UNSPECIFIED && request.getIntervalMs() <= 0) {
            problem = "resolution must be RAW or MINUTE, or an interval given";
        }
        if (problem == null && request.getIntervalMs() > 0 && request.getIntervalMs() < 1_000) {
            problem = "interval_ms must be at least 1000";
        }
        if (problem != null) {
            chunks.onError(Status.INVALID_ARGUMENT.withDescription(problem).asRuntimeException());
            return;
        }
        if (request.getSeriesIdsCount() == 0) {
            chunks.onCompleted();
            return;
        }
        try {
            String method = switch (request.getMethod()) {                 // add-datadog-look K8
                case ROLLUP_SUM -> "sum";
                case ROLLUP_MIN -> "min";
                case ROLLUP_MAX -> "max";
                default -> "avg";
            };
            history.batch(request.getSeriesIdsList(), request.getFromMs(), request.getToMs(),
                    request.getResolution() == Resolution.RESOLUTION_RAW, Math.max(0, request.getIntervalMs()), method,
                    (id, points) -> chunks.onNext(HistoryChunk.newBuilder().setSeriesId(id).addAllPoints(points).build()));
            chunks.onCompleted();
        } catch (RuntimeException e) {
            chunks.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private String rangeProblem(long fromMs, long toMs) {
        if (toMs <= fromMs) {
            return "to must be after from";
        }
        Duration max = settings.get().historyMaxRange();
        if (Duration.ofMillis(toMs - fromMs).compareTo(max) > 0) {
            return "window longer than " + max.toDays() + " days (history-max-range)";
        }
        return null;
    }
}
