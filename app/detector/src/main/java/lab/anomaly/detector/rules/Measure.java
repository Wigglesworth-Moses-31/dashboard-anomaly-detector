package lab.anomaly.detector.rules;

/**
 * How far from normal counts as beyond (design D5, Strategy pattern). Returns the direction the value left normal
 * in, or null when it is not beyond. {@code mad} is only used by the general rule.
 */
public sealed interface Measure {

    Direction beyond(double value, double normal, double mad);

    /** With a wobble factor set, the change must also be at least that many times the line's usual wobble. */
    static boolean outsideWobble(double value, double normal, double mad, double madFactor) {
        return madFactor <= 0 || Double.isNaN(mad) || Math.abs(value - normal) >= madFactor * mad;
    }

    /** Times normal, plus a smallest change: {@code value >= normal * factor} going up, {@code <= normal / factor} down. */
    record Factor(double factor, double minChange, boolean up, boolean down, double madFactor) implements Measure {
        @Override
        public Direction beyond(double value, double normal, double mad) {
            if (!outsideWobble(value, normal, mad, madFactor)) {
                return null;
            }
            if (up && value >= normal * factor && value - normal >= minChange && value > normal) {
                return Direction.UP;
            }
            if (down && value <= normal / factor && normal - value >= minChange && value < normal) {
                return Direction.DOWN;
            }
            return null;
        }
    }

    /** Percentage points: a success rate cannot fall to a third, so 99% to 94% is measured as 5 points. */
    record Points(double points, boolean up, boolean down, double madFactor) implements Measure {
        @Override
        public Direction beyond(double value, double normal, double mad) {
            if (!outsideWobble(value, normal, mad, madFactor)) {
                return null;
            }
            if (up && value - normal >= points) {
                return Direction.UP;
            }
            if (down && normal - value >= points) {
                return Direction.DOWN;
            }
            return null;
        }
    }

    /** The general rule: far outside the line's usual wobble (MAD) and at least {@code atLeast} times or 1/atLeast normal. */
    record Wobble(double madFactor, double atLeast, double minChange, boolean up, boolean down) implements Measure {
        @Override
        public Direction beyond(double value, double normal, double mad) {
            double distance = Math.abs(value - normal);
            double wobble = Math.max(Double.isNaN(mad) ? 0 : mad, 0.01 * Math.abs(normal));
            if (distance < minChange || distance < madFactor * wobble) {
                return null;
            }
            if (up && value > normal && value >= atLeast * normal) {
                return Direction.UP;
            }
            if (down && value < normal && value <= normal / atLeast) {
                return Direction.DOWN;
            }
            return null;
        }
    }
}
