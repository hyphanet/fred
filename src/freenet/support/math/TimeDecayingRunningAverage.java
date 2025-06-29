/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.math;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import freenet.node.TimeSkewDetectorCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Time decaying running average.
 * <p>
 * Decay factor = 0.5 ^ (interval / halflife).
 * <p>
 * So if the interval is exactly the half-life then reporting 0 will halve the value.
 */
public final class TimeDecayingRunningAverage implements RunningAverage, Cloneable {

    private final AtomicReference<Data> data = new AtomicReference<>();
    private final TimeTracker timeTracker;

    /**
     * Monotonic time source in nanosecond resolution (but usually with lower accuracy).
     * This time source should increase monotonically with elapsed time - usually `System::nanoTime`.
     */
    private final LongSupplier monotonicTimeSourceNanos;

    /**
     * Special timestamp set to 1 nanosecond prior to creation of this instance.
     * Used as a sentinel value for "not initialized" timestamps.
     */
    private final long notInitializedSentinel;

    /**
     * Creation time of this instance in wall-clock time. Used for uptime calculation.
     */
    private final long createdTimeMillis;

    /**
     * Half-life time in nanoseconds.
     */
    private final double halfLifeNanos;

    /**
     * Minimum allowed input value. Smaller reported values are silently ignored.
     */
    private final double min;

    /**
     * Maximum allowed input value. Larger reported values are silently ignored.
     */
    private final double max;

    public TimeDecayingRunningAverage(
            double defaultValue,
            long halfLife,
            double min,
            double max,
            TimeSkewDetectorCallback callback
    ) {
        this(defaultValue, halfLife, min, max, null, callback);
    }

    public TimeDecayingRunningAverage(
            double defaultValue,
            long halfLife,
            double min,
            double max,
            SimpleFieldSet fs,
            TimeSkewDetectorCallback callback
    ) {
        this(defaultValue, halfLife, min, max, fs, callback, System::currentTimeMillis, System::nanoTime);
    }

    TimeDecayingRunningAverage(
            double defaultValue,
            long halfLife,
            double min,
            double max,
            SimpleFieldSet fs,
            TimeSkewDetectorCallback callback,
            LongSupplier wallClockTimeSourceMillis,
            LongSupplier monotonicTimeSourceNanos
    ) {
        this.halfLifeNanos = Math.max(1, halfLife) * 1e6;
        this.min = min;
        this.max = max;
        long createdTime = wallClockTimeSourceMillis.getAsLong();
        long reports = 0;
        boolean started = false;
        double currentValue = defaultValue;
        if (fs != null) {
            started = fs.getBoolean("Started", false);
            if (started) {
                double d = fs.getDouble("CurrentValue", currentValue);
                if (!isInvalid(d)) {
                    reports = Math.max(0, fs.getLong("TotalReports", 0));
                    createdTime = createdTime - Math.max(0, fs.getLong("Uptime", 0));
                    currentValue = d;
                }
            }
        }
        this.timeTracker = new TimeTracker(callback, wallClockTimeSourceMillis);
        this.monotonicTimeSourceNanos = monotonicTimeSourceNanos;
        this.notInitializedSentinel = monotonicTimeSourceNanos.getAsLong() - 1;
        this.createdTimeMillis = createdTime;
        this.data.set(new Data(reports, notInitializedSentinel, started, currentValue));
    }

    public TimeDecayingRunningAverage(TimeDecayingRunningAverage other) {
        this.timeTracker = new TimeTracker(other.timeTracker);
        this.monotonicTimeSourceNanos = other.monotonicTimeSourceNanos;
        this.notInitializedSentinel = other.notInitializedSentinel;
        this.createdTimeMillis = other.createdTimeMillis;
        this.halfLifeNanos = other.halfLifeNanos;
        this.max = other.max;
        this.min = other.min;
        this.data.set(new Data(other.data.get()));
    }

    @Override
    public double currentValue() {
        return data.get().currentValue;
    }

    @Override
    public void report(double d) {
        data.updateAndGet(data -> data.updated(d));
        timeTracker.report();
    }

    @Override
    public void report(long d) {
        report((double) d);
    }

    @Override
    public double valueIfReported(double r) {
        return data.get().updated(r).currentValue;
    }

    @Override
    public long countReports() {
        return data.get().reports;
    }

    public long lastReportTime() {
        return timeTracker.lastReportMillis;
    }

    public SimpleFieldSet exportFieldSet(boolean shortLived) {
        Data data = this.data.get();
        long now = timeTracker.wallClockTimeSourceMillis.getAsLong();
        SimpleFieldSet fs = new SimpleFieldSet(shortLived);
        fs.putSingle("Type", "TimeDecayingRunningAverage");
        fs.put("CurrentValue", data.currentValue);
        fs.put("Started", data.started);
        fs.put("TotalReports", data.reports);
        fs.put("Uptime", now - createdTimeMillis);
        return fs;
    }

    @Override
    public TimeDecayingRunningAverage clone() {
        return new TimeDecayingRunningAverage(this);
    }

    @Override
    public String toString() {
        Data data = this.data.get();
        long now = timeTracker.wallClockTimeSourceMillis.getAsLong();
        return super.toString() +
                ": currentValue=" + data.currentValue + ", " +
                ", halfLife=" + (long) (halfLifeNanos / 1e6) + "ms" +
                ", lastReportTime=" + (now - lastReportTime()) + "ms ago" +
                ", createdTime=" + (now - createdTimeMillis) + "ms ago" +
                ", reports=" + data.reports +
                ", started=" + data.started +
                ", min=" + min +
                ", max=" + max;
    }

    private boolean isInvalid(double d) {
        return d < min || d > max || Double.isInfinite(d) || Double.isNaN(d);
    }

    private class Data {
        private final long reports;
        private final long lastUpdatedNanos;
        private final boolean started;
        private final double currentValue;

        private Data(long reports, long lastUpdatedNanos, boolean started, double currentValue) {
            this.reports = reports;
            this.lastUpdatedNanos = lastUpdatedNanos;
            this.started = started;
            this.currentValue = Math.max(min, Math.min(max, currentValue));
        }

        private Data(Data other) {
            this.reports = other.reports;
            this.lastUpdatedNanos = other.lastUpdatedNanos;
            this.started = other.started;
            this.currentValue = other.currentValue;
        }

        private Data updated(double d) {
            if (isInvalid(d)) {
                return this;
            }
            long now = monotonicTimeSourceNanos.getAsLong();
            if (!started) {
                // A fresh average instantly jumps to the first reported value
                return new Data(reports + 1, now, true, d);
            }
            if (lastUpdatedNanos == notInitializedSentinel) {
                // For a restored average, the first data point is ignored
                return new Data(reports + 1, now, true, currentValue);
            }
            double timeSinceLastUpdated = now - lastUpdatedNanos;
            /* close to 1.0 if short interval, close to 0.0 if long interval */
            double changeFactor = Math.pow(0.5, timeSinceLastUpdated / halfLifeNanos);
            double newValue = currentValue * changeFactor + (1.0 - changeFactor) * d;
            return new Data(reports + 1, now, true, newValue);
        }
    }

    private static class TimeTracker {
        /**
         * Callback to invoke when a time skew is detected.
         */
        private final TimeSkewDetectorCallback timeSkewDetectorCallback;

        /**
         * Time source reporting the wall-clock time in milliseconds since the UNIX epoch.
         * This clock should represent the system time which may drift due to (network) time synchronization.
         * Usually set to `System::currentTimeMillis`.
         */
        private final LongSupplier wallClockTimeSourceMillis;

        /**
         * Timestamp when `report` was last invoked, in milliseconds since UNIX epoch.
         */
        private volatile long lastReportMillis = -1;

        private TimeTracker(TimeSkewDetectorCallback timeSkewDetectorCallback, LongSupplier wallClockTimeSourceMillis) {
            this.timeSkewDetectorCallback = timeSkewDetectorCallback;
            this.wallClockTimeSourceMillis = wallClockTimeSourceMillis;
        }

        private TimeTracker(TimeTracker other) {
            this.timeSkewDetectorCallback = other.timeSkewDetectorCallback;
            this.wallClockTimeSourceMillis = other.wallClockTimeSourceMillis;
            this.lastReportMillis = other.lastReportMillis;
        }

        private void report() {
            if (timeSkewDetectorCallback == null) {
                this.lastReportMillis = wallClockTimeSourceMillis.getAsLong();
                return;
            }
            long lastReportTime = this.lastReportMillis;
            long now = wallClockTimeSourceMillis.getAsLong();
            this.lastReportMillis = now;
            if (now < lastReportTime) {
                Logger.error(this, "Clock went back in time: " + now + " was " + lastReportTime + " (back " + (lastReportTime - now) + "ms)");
                timeSkewDetectorCallback.setTimeSkewDetectedUserAlert();
            }
        }
    }
}
