package freenet.support.math;

import java.util.ArrayList;
import java.util.Collections;

/**
 * A RunningAverage that tracks both the median and mean of a series of values.
 * WARNING: Uses memory and proportional to the number of reports! Only for debugging!
 * (Also uses CPU time O(N log N) with the number of reports in currentValue()).
 *
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public final class MedianMeanRunningAverage implements RunningAverage, Cloneable {

    private final ArrayList<Double> reports = new ArrayList<>();
    private final TrivialRunningAverage mean;

    public MedianMeanRunningAverage() {
        mean = new TrivialRunningAverage();
    }

    public MedianMeanRunningAverage(MedianMeanRunningAverage other) {
        synchronized (other.reports) {
            this.mean = new TrivialRunningAverage(other.mean);
            this.reports.addAll(other.reports);
        }
    }

    @Override
    public MedianMeanRunningAverage clone() {
        return new MedianMeanRunningAverage(this);
    }

    @Override
    public long countReports() {
        synchronized (reports) {
            return reports.size();
        }
    }

    @Override
    public double currentValue() {
        synchronized (reports) {
            int size = reports.size();
            int middle = size / 2;
            Collections.sort(reports);
            return reports.get(middle);
        }
    }

    @Override
    public void report(double d) {
        synchronized (reports) {
            mean.report(d);
            reports.add(d);
        }
    }

    @Override
    public void report(long d) {
        report((double) d);
    }

    @Override
    public double valueIfReported(double r) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        synchronized (reports) {
            return "Median " + currentValue() + " mean " + meanValue();
        }
    }

    public double meanValue() {
        return mean.currentValue();
    }

}
