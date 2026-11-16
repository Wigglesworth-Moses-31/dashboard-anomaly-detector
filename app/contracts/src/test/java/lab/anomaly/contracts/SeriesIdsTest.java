package lab.anomaly.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeriesIdsTest {

    private static final String QUERY_ID = SeriesIds.queryId("sum:lab.api.request.count{*} by {api,status_code}");

    private static String id(Map<String, String> tags) {
        return SeriesIds.seriesId("datadog", "datadoghq.com", QUERY_ID, tags);
    }

    @Test
    void tagOrderDoesNotMatter() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("api", "/v1/invoices");
        one.put("status_code", "500");
        Map<String, String> other = new LinkedHashMap<>();
        other.put("status_code", "500");
        other.put("api", "/v1/invoices");

        assertThat(id(one)).isEqualTo(id(other));
    }

    @Test
    void anyDifferentTagGivesADifferentId() {
        assertThat(id(Map.of("api", "/v1/invoices", "status_code", "500")))
                .isNotEqualTo(id(Map.of("api", "/v1/invoices", "status_code", "200")));
    }

    @Test
    void separatorsInsideValuesCannotCauseAClash() {
        // With a plain "name=value,..." form both of these would read "a=1,b=2"
        assertThat(id(Map.of("a", "1,b=2"))).isNotEqualTo(id(Map.of("a", "1", "b", "2")));
    }

    @Test
    void queryIdIgnoresExtraSpaces() {
        assertThat(SeriesIds.queryId("  sum:x{*}   by {api} ")).isEqualTo(SeriesIds.queryId("sum:x{*} by {api}"));
    }

    @Test
    void fixedInputGivesAFixedId() {
        // Expected values computed independently in Python with the same definition (design C3),
        // so any other language can reproduce these ids.
        assertThat(SeriesIds.queryId("sum:lab.api.request.count{env:prod} by {api,status_code}.as_count()"))
                .isEqualTo("b48b75587ccb3b95");
        assertThat(SeriesIds.seriesId("datadog", "datadoghq.com", "0123456789abcdef",
                Map.of("api", "/v1/invoices", "status_code", "500")))
                .isEqualTo("b5a8ea748c0b4560");
    }

    @Test
    void idFormat() {
        assertThat(SeriesIds.isId(id(Map.of()))).isTrue();
        assertThat(SeriesIds.isId("ABCDEF0123456789")).isFalse();
        assertThat(SeriesIds.isId("abc")).isFalse();
        assertThat(SeriesIds.isId(null)).isFalse();
    }
}
