package lablink;

import java.util.ArrayList;
import java.util.List;

/** Buffers samples on the host clock so network arrival jitter does not drive the bob. */
final class SwingTrace {
    record Sample(double seconds, double angle) {}

    static final double PLOT_GAP = .16;
    private static final long PLAYBACK_DELAY = 75_000_000;
    private final ArrayList<Sample> points = new ArrayList<>();
    private double duration;
    private double releaseAngle;
    private long sequence = -1;
    private long missing;
    private double lastTime = -1;
    private long timestampOrigin;
    private boolean clockSet;
    private boolean finished;

    void clear() {
        points.clear();
        duration = releaseAngle = 0;
        sequence = -1;
        lastTime = -1;
        missing = 0;
        timestampOrigin = 0;
        clockSet = finished = false;
    }

    void start(double seconds, double angle) {
        clear();
        duration = seconds;
        releaseAngle = angle;
        points.add(new Sample(0, angle));
    }

    boolean accept(long number, long millis, double angle, long arrivalNanos) {
        double seconds = millis / 1000.0;
        if (duration <= 0 || finished || number < 0 || number <= sequence ||
                millis < 0 || seconds >= duration || seconds <= lastTime ||
                !Double.isFinite(angle) || Math.abs(angle) > Math.abs(releaseAngle) + .01) return false;
        if (!clockSet) {
            timestampOrigin = arrivalNanos - millis * 1_000_000;
            clockSet = true;
        }
        missing += number - sequence - 1;
        sequence = number;
        lastTime = seconds;
        if (seconds > 0) points.add(new Sample(seconds, angle));
        return true;
    }

    void finish() {
        if (finished || duration <= 0) return;
        finished = true;
        points.add(new Sample(duration, releaseAngle));
    }

    double angleAt(long now) {
        if (!clockSet) return releaseAngle;
        double seconds = Math.max(0, (now - timestampOrigin - PLAYBACK_DELAY) / 1_000_000_000.0);
        Sample previous = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            Sample next = points.get(i);
            if (seconds <= next.seconds) {
                double gap = next.seconds - previous.seconds;
                if (gap > PLOT_GAP && seconds < next.seconds - .05) return previous.angle;
                double fraction = Math.min(1, (seconds - previous.seconds) / Math.min(gap, PLOT_GAP));
                if (gap > PLOT_GAP) fraction = Math.max(0, (seconds - next.seconds + .05) / .05);
                return previous.angle + (next.angle - previous.angle) * fraction;
            }
            previous = next;
        }
        return previous.angle;
    }

    List<Sample> points() {
        return points;
    }

    long received() {
        return sequence + 1 - missing;
    }

    long missing() {
        return missing;
    }
}
