package freenet.support.math;

import java.util.ArrayList;
import java.util.TreeSet;

public class MedianMeanRunningAverage implements RunningAverage {
	
	final ArrayList<Double> reports;
	final TrivialRunningAverage mean;

	public MedianMeanRunningAverage() {
		reports = new ArrayList<Double>();
		mean = new TrivialRunningAverage();
	}

	public MedianMeanRunningAverage(MedianMeanRunningAverage average) {
		this.mean = new TrivialRunningAverage(average.mean);
		this.reports = new ArrayList<Double>();
		reports.addAll(average.reports);
	}

	public Object clone() {
		return new MedianMeanRunningAverage(this);
	}

	public synchronized long countReports() {
		return reports.size();
	}

	public synchronized double currentValue() {
		int size = reports.size();
		int middle = size / 2;
		java.util.Collections.sort(reports);
		return reports.get(middle);
	}

	public synchronized void report(double d) {
		mean.report(d);
		reports.add(d);
	}

	public void report(long d) {
		report((double)d);
	}

	public double valueIfReported(double r) {
		throw new UnsupportedOperationException();
	}
	
	public synchronized String toString() {
		return "Median "+currentValue()+" mean "+mean.currentValue();
	}
	
	public synchronized double meanValue() {
		return mean.currentValue();
	}

}
