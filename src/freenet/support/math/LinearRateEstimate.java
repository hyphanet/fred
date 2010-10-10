/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.math;

/**
 * Calculate the linear rate of change of reported values over a given slice of recent history.
 *
 * It is the instantiator's responsibility to call the report() method at regular intervals close to the specified updateInterval.
 */
public class LinearRateEstimate implements RunningAverage {
	private static final long serialVersionUID = -1;
	final int historyDuration;
	final double[] reports;
	final double[] timestamps;
	int nextSlotPtr;
	long totalReports;

	@Override
	public final Object clone() {
		return new LinearRateEstimate(this);
	}

	private LinearRateEstimate(LinearRateEstimate r) {
		synchronized(r) {
			this.historyDuration = r.historyDuration;
			this.reports = r.reports.clone();
			this.timestamps = r.timestamps.clone();
			this.nextSlotPtr = r.nextSlotPtr;
			this.totalReports = r.totalReports;
		}
	}

	public LinearRateEstimate(int historyDuration, int updateInterval) {
		this.historyDuration = historyDuration;
		int length = historyDuration / updateInterval + 1;
		this.reports = new double[length];
		this.timestamps = new double[length];
		clear();
	}

	public synchronized void clear() {
		nextSlotPtr = 0;
		totalReports = 0;
	}

	public synchronized void report(long x) {
		report((double)x);
	}

	public synchronized void report(double x) {
		reports[nextSlotPtr] = x;
		timestamps[nextSlotPtr] = System.currentTimeMillis();
		nextSlotPtr = incrPtr(nextSlotPtr);
		++totalReports;
	}

	public synchronized long countReports() {
		return totalReports;
	}

	private final int incrPtr(int ptr) {
		return (ptr+1) % reports.length;
	}
	private final int decrPtr(int ptr) {
		return ptr==0 ? reports.length-1 : ptr-1;
	}

	public synchronized double currentValue() {
		if(totalReports < 2)
			return 0.0;
		int currentSlotPtr = decrPtr(nextSlotPtr);
		return calculateRate(reports[currentSlotPtr], timestamps[currentSlotPtr]);
	}

	public synchronized double valueIfReported(double x) {
		if(totalReports < 1)
			return 0.0;
		return calculateRate(x, System.currentTimeMillis());
	}

	private double calculateRate(double currentReport, double currentTimestamp) {
		int currentSlotPtr = decrPtr(nextSlotPtr);
		int oldestValidReportPtr = currentSlotPtr;

		// rewind oldestValidReportPtr until it points to the oldest report that is not older than historyDuration
		while(true) {
			oldestValidReportPtr = decrPtr(oldestValidReportPtr);
			if(timestamps[oldestValidReportPtr] - currentTimestamp > historyDuration)
				break;
			if(oldestValidReportPtr == currentSlotPtr)
				break;
			if(totalReports == nextSlotPtr && oldestValidReportPtr== reports.length-1)
				break;
		}
		oldestValidReportPtr = incrPtr(oldestValidReportPtr);
		return (currentReport - reports[oldestValidReportPtr]) / (currentTimestamp - timestamps[oldestValidReportPtr]) * 1000;
	}

}
