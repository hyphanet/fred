package freenet.support.math;

import java.util.ArrayList;

/**
 * A RunningAverage that tracks both the median and mean of a series of values.
 * WARNING: Uses memory and proportional to the number of reports! Only for debugging!
 * (Also uses CPU time O(N log N) with the number of reports in currentValue()).
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public final class MedianMeanRunningAverage implements RunningAverage, Cloneable {
	private static final long serialVersionUID = 1L;

	final ArrayList<Double> reports;
	final TrivialRunningAverage mean;

        /**
         *
         */
        public MedianMeanRunningAverage() {
		reports = new ArrayList<Double>();
		mean = new TrivialRunningAverage();
	}

        /**
         *
         * @param average
         */
        public MedianMeanRunningAverage(MedianMeanRunningAverage average) {
		this.mean = new TrivialRunningAverage(average.mean);
		this.reports = new ArrayList<Double>();
		reports.addAll(average.reports);
	}

	@Override
	public MedianMeanRunningAverage clone() {
		// Override clone() for synchronization.
		// Implement Cloneable to shut up findbugs.
		synchronized (this) {
			return new MedianMeanRunningAverage(this);
		}
	}

	@Override
	public synchronized long countReports() {
		return reports.size();
	}

        /**
         *
         * @return
         */
        @Override
        public synchronized double currentValue() {
		int size = reports.size();
		int middle = size / 2;
		java.util.Collections.sort(reports);
		return reports.get(middle);
	}

        /**
         *
         * @param d
         */
        @Override
        public synchronized void report(double d) {
		mean.report(d);
		reports.add(d);
	}

        /**
         *
         * @param d
         */
        @Override
        public void report(long d) {
		report((double)d);
	}

	@Override
	public double valueIfReported(double r) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public synchronized String toString() {
		return "Median "+currentValue()+" mean "+mean.currentValue();
	}
	
        /**
         *
         * @return
         */
        public synchronized double meanValue() {
		return mean.currentValue();
	}

}
