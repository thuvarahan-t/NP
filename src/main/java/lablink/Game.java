package lablink;

final class Game {
    static final double TARGET = 2;
    static final double MIN_LENGTH = 0.25;
    static final double MAX_LENGTH = 2;
    static final double AMPLITUDE = 10;

    static boolean validLength(double length) {
        return Double.isFinite(length) && length >= MIN_LENGTH && length <= MAX_LENGTH;
    }

    static double period(double length) {
        if (!validLength(length)) throw new IllegalArgumentException("Invalid length");
        return 2 * Math.PI * Math.sqrt(length / 9.81);
    }

    static double angle(double seconds, double period) {
        return AMPLITUDE * Math.cos(2 * Math.PI * seconds / period);
    }

    static double error(double period) {
        return Math.abs(period - TARGET);
    }
}
