/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.math;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;

/**
 * Exponential decay "running average".
 * 
 * @author amphibian
 * 
 * For the first <tt>maxReports</tt> reports, this is equivalent to a simple running average.
 * After that it is a decaying running average with a <tt>decayFactor</tt> of <tt>1 / maxReports</tt>. We
 * accomplish this by having <tt>decayFactor = 1/(Math.min(#reports, maxReports))</tt>. We can
 * therefore:
 * <ul>
 * <li>Specify <tt>maxReports</tt> more easily than an arbitrary decay factor.</li>
 * <li>We don't get big problems with influence of the initial value, which is usually not very reliable.</li>
 * </ul>
 */
public final class BootstrappingDecayingRunningAverage implements RunningAverage {
	private static final long serialVersionUID = -1;
	@Override
	public final BootstrappingDecayingRunningAverage clone() {
		return new BootstrappingDecayingRunningAverage(this);
	}
    
	private final double min;
	private final double max;
	private double currentValue;
	private long reports;
	private int maxReports;

        private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}
    
	/**
	 * Constructor
	 * 
	 * @param defaultValue
	 *                default value
	 * @param min
	 *                minimum value of input data
	 * @param max
	 *                maxumum value of input data
	 * @param maxReports
	 *		  number of reports before bootstrapping period ends and decay begins
	 * @param fs
	 *                {@link SimpleFieldSet} parameter for this object. Will
	 *                override other parameters.
	 */
	public BootstrappingDecayingRunningAverage(double defaultValue, double min,
			double max, int maxReports, SimpleFieldSet fs) {
		this.min = min;
		this.max = max;
		reports = 0;
		currentValue = defaultValue;
		this.maxReports = maxReports;
		assert(maxReports > 0);
		if(fs != null) {
			double d = fs.getDouble("CurrentValue", currentValue);
			if(!(Double.isNaN(d) || d < min || d > max)) {
				currentValue = d;
				reports = fs.getLong("Reports", reports);
			}
		}
	}
    
	/**
	 * {@inheritDoc}
         *
         * @return
         */
	@Override
	public synchronized double currentValue() {
		return currentValue;
	}
			
	/**
	 * <strong>Not a public method.</strong> Changes the internally stored <code>currentValue</code> and return the old one.
	 * 
	 * Used by {@link DecayingKeyspaceAverage} to normalize the stored averages. Calling this function
	 * may (purposefully) destroy the utility of the average being kept.
	 * 
         * @param d
         * @return
         * @see DecayingKeyspaceAverage
	 */
	protected synchronized double setCurrentValue(double d) {
		double old=currentValue;
		currentValue=d;
		return old;
	}

	/**
	 * {@inheritDoc}
         *
         * @param d
         */
	@Override
	public synchronized void report(double d) {
		if(d < min) {
			if(logDEBUG)
				Logger.debug(this, "Too low: "+d, new Exception("debug"));
			d = min;
		}
		if(d > max) {
			if(logDEBUG)
				Logger.debug(this, "Too high: "+d, new Exception("debug"));
			d = max;
		}
		reports++;
		double decayFactor = 1.0 / (Math.min(reports, maxReports));
		currentValue = (d * decayFactor) + (currentValue * (1-decayFactor));
	}

	/**
	 * {@inheritDoc}
         *
         * @param d
         */
	@Override
	public void report(long d) {
		report((double)d);
	}

	/**
	 * {@inheritDoc}
         *
         * @param d
         */
	@Override
	public synchronized double valueIfReported(double d) {
		if(d < min) {
			Logger.error(this, "Too low: "+d, new Exception("debug"));
			d = min;
		}
		if(d > max) {
			Logger.error(this, "Too high: "+d, new Exception("debug"));
			d = max;
		}
		double decayFactor = 1.0 / (Math.min(reports + 1, maxReports));
		return (d * decayFactor) + (currentValue * (1-decayFactor));
	}
    
	/**
	 * Change <code>maxReports</code>.
	 * 
	 * @param maxReports
	 */
	public synchronized void changeMaxReports(int maxReports) {
		this.maxReports=maxReports;
	}

	/**
	 * Copy constructor.
	 */
	private BootstrappingDecayingRunningAverage(BootstrappingDecayingRunningAverage a) {
		synchronized (a) {
			this.currentValue = a.currentValue;
			this.max = a.max;
			this.maxReports = a.maxReports;
			this.min = a.min;
			this.reports = a.reports;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized  long countReports() {
		return reports;
	}

	/**
	 * Export this object as {@link SimpleFieldSet}.
	 * 
	 * @param shortLived
         * 		See {@link SimpleFieldSet#SimpleFieldSet(boolean)}.
         * @return
	 */
	public synchronized SimpleFieldSet exportFieldSet(boolean shortLived) {
		SimpleFieldSet fs = new SimpleFieldSet(shortLived);
		fs.putSingle("Type", "BootstrappingDecayingRunningAverage");
		fs.put("CurrentValue", currentValue);
		fs.put("Reports", reports);
		return fs;
	}
}
