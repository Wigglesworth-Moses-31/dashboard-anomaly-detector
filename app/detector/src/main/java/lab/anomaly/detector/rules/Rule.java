package lab.anomaly.detector.rules;

/**
 * A compiled rule. {@code name} identifies it on the health page ("rules[3] latency" or "general").
 *
 * @param needsMad only the general rule needs the line's wobble
 */
public record Rule(String name, String signal, Measure measure, int steps, boolean needsMad) {

    public boolean isGeneral() {
        return "general".equals(name);
    }
}
