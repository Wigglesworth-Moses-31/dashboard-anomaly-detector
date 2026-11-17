package lab.anomaly.kit.settings;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * One way to write a moment in several zones, the main one first (add-display-time-zones design Z3):
 * {@code 13:04:10 UTC · 18:34:10 IST · 08:04:10 EST}; a zone on another calendar day than the main one adds its date,
 * {@code 04:40 IST (18 Nov)}. English zone names: UTC, IST, EST/EDT follow the date by themselves. O(zones).
 */
public final class Times {

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss z", Locale.ENGLISH);
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("HH:mm z", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    public static String format(Instant at, List<ZoneId> zones, boolean seconds) {
        StringJoiner out = new StringJoiner(" · ");
        LocalDate mainDay = null;
        for (ZoneId zone : zones) {
            ZonedDateTime local = at.atZone(zone);
            String text = (seconds ? SECONDS : MINUTES).format(local);
            if (mainDay == null) {
                mainDay = local.toLocalDate();
            } else if (!local.toLocalDate().equals(mainDay)) {
                text += " (" + DAY.format(local) + ")";
            }
            out.add(text);
        }
        return out.toString();
    }

    public static String format(long epochMs, List<ZoneId> zones, boolean seconds) {
        return format(Instant.ofEpochMilli(epochMs), zones, seconds);
    }

    private Times() {
    }
}
