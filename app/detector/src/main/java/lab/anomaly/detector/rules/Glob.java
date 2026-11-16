package lab.anomaly.detector.rules;

import java.util.regex.Pattern;

/** A pattern from the settings: {@code *} matches anything, everything else literally, ignoring case. */
record Glob(String text, Pattern pattern) {

    static Glob of(String text) {
        StringBuilder regex = new StringBuilder();
        for (String part : text.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return new Glob(text, Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
    }

    boolean matches(String value) {
        return value != null && pattern.matcher(value).matches();
    }
}
