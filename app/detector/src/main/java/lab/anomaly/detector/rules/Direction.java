package lab.anomaly.detector.rules;

/** Which way a value left normal. */
public enum Direction {
    UP, DOWN;

    public String word() {
        return this == UP ? "up" : "down";
    }
}
