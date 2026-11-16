package lab.anomaly.kit.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.mock.env.MockEnvironment;

class SettingsHolderTest {

    record PollSettings(Duration pollInterval) {}

    private static final List<SettingsValidator<? super PollSettings>> CHECKS =
            List.of(SettingsValidator.positive("poll-interval", PollSettings::pollInterval));

    private final MockEnvironment environment = new MockEnvironment().withProperty("anomaly.test.poll-interval", "15s");
    private final SettingsHolder<PollSettings> holder =
            new SettingsHolder<>("anomaly.test", PollSettings.class, CHECKS, environment);

    private void changed(String key) {
        holder.onApplicationEvent(new EnvironmentChangeEvent(this, Set.of(key)));
    }

    @Test
    void validChangeIsSwappedIn() {
        environment.setProperty("anomaly.test.poll-interval", "30s");
        changed("anomaly.test.poll-interval");

        assertThat(holder.get().pollInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(holder.snapshot().version()).isEqualTo(2);
        assertThat(holder.lastRejection()).isNull();
    }

    @Test
    void invalidChangeKeepsTheLastGoodSettings() {
        environment.setProperty("anomaly.test.poll-interval", "-5s");
        changed("anomaly.test.poll-interval");

        assertThat(holder.get().pollInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(holder.snapshot().version()).isEqualTo(1);
        assertThat(holder.lastRejection().problems()).singleElement().asString().contains("poll-interval");
    }

    @Test
    void unreadableValueIsRejectedToo() {
        environment.setProperty("anomaly.test.poll-interval", "-5 seconds");
        changed("anomaly.test.poll-interval");

        assertThat(holder.get().pollInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(holder.lastRejection()).isNotNull();
    }

    @Test
    void fixingTheValueClearsTheRejection() {
        environment.setProperty("anomaly.test.poll-interval", "-5s");
        changed("anomaly.test.poll-interval");
        environment.setProperty("anomaly.test.poll-interval", "20s");
        changed("anomaly.test.poll-interval");

        assertThat(holder.get().pollInterval()).isEqualTo(Duration.ofSeconds(20));
        assertThat(holder.lastRejection()).isNull();
    }

    @Test
    void unrelatedChangesAreIgnored() {
        environment.setProperty("anomaly.test.poll-interval", "30s");
        changed("anomaly.other.something");

        assertThat(holder.snapshot().version()).isEqualTo(1);
    }

    @Test
    void invalidSettingsAtStartStopTheService() {
        MockEnvironment bad = new MockEnvironment().withProperty("anomaly.test.poll-interval", "0s");

        assertThatThrownBy(() -> new SettingsHolder<>("anomaly.test", PollSettings.class, CHECKS, bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("poll-interval");
    }
}
