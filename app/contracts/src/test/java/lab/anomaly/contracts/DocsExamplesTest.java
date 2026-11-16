package lab.anomaly.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.MessageOrBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps docs/contracts.md honest: every JSON example in it must be exactly what ContractJson produces for
 * the shared Examples. Refresh after a contract change: {@code mvn -pl contracts test -Dcontracts.updateDocs=true}
 */
class DocsExamplesTest {

    private static final Path DOC = Path.of("../../docs/contracts.md");   // from app/contracts

    private static Map<String, MessageOrBuilder> examples() {
        Map<String, MessageOrBuilder> examples = new LinkedHashMap<>();
        examples.put("series-descriptor", Examples.descriptor());
        examples.put("metric-points", Examples.points());
        examples.put("incident-event", Examples.incidentEvent());
        return examples;
    }

    private static Pattern block(String name) {
        return Pattern.compile("(<!-- example: " + Pattern.quote(name) + " -->\\n```json\\n)(.*?)(```)", Pattern.DOTALL);
    }

    @Test
    void examplesInTheDocsAreProducedByContractJson() throws Exception {
        String doc = Files.readString(DOC);
        if (Boolean.getBoolean("contracts.updateDocs")) {
            for (var example : examples().entrySet()) {
                Matcher m = block(example.getKey()).matcher(doc);
                assertThat(m.find()).as("marker for " + example.getKey()).isTrue();
                String json = ContractJson.toPrettyJson(example.getValue()) + "\n";
                doc = doc.substring(0, m.start(2)) + json + doc.substring(m.end(2));
            }
            Files.writeString(DOC, doc);
        }
        for (var example : examples().entrySet()) {
            Matcher m = block(example.getKey()).matcher(doc);
            assertThat(m.find()).as("example block '" + example.getKey() + "' in docs/contracts.md").isTrue();
            assertThat(m.group(2)).as("JSON of '" + example.getKey() + "' in docs/contracts.md")
                    .isEqualTo(ContractJson.toPrettyJson(example.getValue()) + "\n");
        }
    }
}
