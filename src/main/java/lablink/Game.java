package lablink;

final class Game {
    static final double TARGET = 2;
    static final double MIN_LENGTH = 0.25;
    static final double MAX_LENGTH = 2;
    static final double MIN_ANGLE = 5;
    static final double MAX_ANGLE = 65;
    static final double MIN_TARGET = 1.20;
    static final double MAX_TARGET = 2.90;
    private static final double GRAVITY = 9.81;

    static boolean validLength(double length) {
        return Double.isFinite(length) && length >= MIN_LENGTH && length <= MAX_LENGTH;
    }

    static boolean validAngle(double angle) {
        return Double.isFinite(angle) && Math.abs(angle) >= MIN_ANGLE && Math.abs(angle) <= MAX_ANGLE;
    }

    static double period(double length, double angle) {
        if (!validLength(length) || !validAngle(angle)) throw new IllegalArgumentException("Invalid release");
        // The complete elliptic integral gives the ideal pendulum's nonlinear period.
        double a = 1;
        double b = Math.cos(Math.toRadians(angle) / 2);
        while (Math.abs(a - b) > 1e-13) {
            double mean = (a + b) / 2;
            b = Math.sqrt(a * b);
            a = mean;
        }
        return 2 * Math.PI * Math.sqrt(length / GRAVITY) / a;
    }

    static double error(double period, double target) {
        return Math.abs(period - target);
    }

    static final class Motion {
        private final double factor;
        private double angle;
        private double velocity;
        private double time;

        Motion(double length, double releaseDegrees) {
            factor = GRAVITY / length;
            angle = Math.toRadians(releaseDegrees);
        }

        double advanceTo(double seconds) {
            // Integrate theta'' = -(g / L) sin(theta) with bounded RK4 steps.
            while (time < seconds) {
                double step = Math.min(.002, seconds - time);
                double half = step / 2;
                double a1 = acceleration(angle);
                double v2 = velocity + half * a1;
                double a2 = acceleration(angle + half * velocity);
                double v3 = velocity + half * a2;
                double a3 = acceleration(angle + half * v2);
                double v4 = velocity + step * a3;
                double a4 = acceleration(angle + step * v3);
                angle += step * (velocity + 2 * v2 + 2 * v3 + v4) / 6;
                velocity += step * (a1 + 2 * a2 + 2 * a3 + a4) / 6;
                time += step;
            }
            return Math.toDegrees(angle);
        }

        private double acceleration(double radians) {
            return -factor * Math.sin(radians);
        }
    }
}
