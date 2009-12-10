package freenet.support.math;

public class TrivialRunningAverage implements RunningAverage {

	private static final long serialVersionUID = 1L;
	private long reports;
	private double total;

	/**
	 *
	 * @param average
	 */
	public TrivialRunningAverage(TrivialRunningAverage average) {
		this.reports = average.reports;
		this.total = average.total;
	}

	/**
	 *
	 */
	public TrivialRunningAverage() {
		reports = 0;
		total = 0.0;
	}

	public synchronized long countReports() {
		return reports;
	}

	/**
	 *
	 * @return
	 */
	public synchronized double currentValue() {
		return total / reports;
	}

	/**
	 *
	 * @param d
	 */
	public synchronized void report(double d) {
		total += d;
		reports++;
		// TODO Auto-generated method stub
	}

	/**
	 *
	 * @param d
	 */
	public void report(long d) {
		report((double) d);
	}

	public synchronized double valueIfReported(double r) {
		return (total + r) / (reports + 1);
	}

	@Override
	public Object clone() {
		synchronized(this) {
			return new TrivialRunningAverage(this);
		}
	}

}
