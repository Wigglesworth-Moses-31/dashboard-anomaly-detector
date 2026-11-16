package lab.anomaly.reporter.window;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One report window (design R1): starts at midnight in the shared time zone plus a multiple of the window length.
 * The key is also the file name: {@code 2026-11-16} for a whole day, {@code 2026-11-16_06-12} otherwise.
 */
public record ReportWindow(String key, LocalDate day, long startMs, long endMs, ZoneId zone) {

    public static ReportWindow of(LocalDate day, int index, Duration length, ZoneId zone) {
        long hours = length.toHours();
        ZonedDateTime midnight = day.atStartOfDay(zone);
        ZonedDateTime start = midnight.plusHours(index * hours);
        ZonedDateTime end = midnight.plusHours((index + 1) * hours);
        String key = hours == 24 ? day.toString() : String.format("%s_%02d-%02d", day, index * hours, (index + 1) * hours);
        return new ReportWindow(key, day, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli(), zone);
    }

    /** Every window of one day, in order. */
    public static List<ReportWindow> ofDay(LocalDate day, Duration length, ZoneId zone) {
        List<ReportWindow> out = new ArrayList<>();
        for (int i = 0; i < 24 / length.toHours(); i++) {
            out.add(of(day, i, length, zone));
        }
        return out;
    }

    /**
     * Windows that ended at least {@code grace} ago and not more than {@code catchUp} ago, oldest first:
     * the windows that should have a report by now.
     */
    public static List<ReportWindow> due(long nowMs, Duration length, Duration grace, Duration catchUp, ZoneId zone) {
        List<ReportWindow> out = new ArrayList<>();
        LocalDate first = Instant.ofEpochMilli(nowMs - catchUp.toMillis()).atZone(zone).toLocalDate();
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            for (ReportWindow w : ofDay(d, length, zone)) {
                if (w.endMs() + grace.toMillis() <= nowMs && w.endMs() > nowMs - catchUp.toMillis()) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    /** The window just before this one (for "yesterday 29"). */
    public ReportWindow previous() {
        long hours = Duration.ofMillis(endMs - startMs).toHours() >= 23 ? 24 : Duration.ofMillis(endMs - startMs).toHours();
        int index = (int) (Duration.between(day.atStartOfDay(zone).toInstant(), Instant.ofEpochMilli(startMs)).toHours() / hours);
        return index == 0 ? of(day.minusDays(1), (int) (24 / hours) - 1, Duration.ofHours(hours), zone)
                : of(day, index - 1, Duration.ofHours(hours), zone);
    }

    public boolean wholeDay() {
        return Duration.ofMillis(endMs - startMs).toHours() >= 23;    // 23 or 25 hours on a clock-change day
    }

    public String fileName() {
        return key + ".md";
    }
}
