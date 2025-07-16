/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.math;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import freenet.support.SimpleFieldSet;

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
public final class BootstrappingDecayingRunningAverage implements RunningAverage, Cloneable {

	private final AtomicReference<Data> data = new AtomicReference<>();
	private final double min;
	private final double max;

	/**
	 * Constructor
	 * 
	 * @param defaultValue
	 *                default value
	 * @param min
	 *                minimum value of input data
	 * @param max
	 *                maximum value of input data
	 * @param maxReports
	 *		  number of reports before bootstrapping period ends and decay begins
	 * @param fs
	 *                {@link SimpleFieldSet} parameter for this object. Will
	 *                override other parameters.
	 */
	public BootstrappingDecayingRunningAverage(
			double defaultValue,
			double min,
			double max,
			int maxReports,
			SimpleFieldSet fs
	) {
		this.min = min;
		this.max = max;
		long reports = 0;
		double currentValue = defaultValue;
		if (fs != null) {
			double d = fs.getDouble("CurrentValue", currentValue);
			if (!isInvalid(d)) {
				reports = Math.max(0, fs.getLong("Reports", reports));
				currentValue = d;
			}
		}
		data.set(new Data(maxReports, reports, currentValue));
	}

	private BootstrappingDecayingRunningAverage(BootstrappingDecayingRunningAverage other) {
		this.min = other.min;
		this.max = other.max;
		this.data.set(new Data(other.data.get()));
	}

	@Override
	public double currentValue() {
		return data.get().currentValue;
	}

	@Override
	public void report(double d) {
		data.updateAndGet(data -> data.updated(d));
	}

	@Override
	public void report(long d) {
		report((double)d);
	}

	@Override
	public double valueIfReported(double d) {
		return data.get().updated(d).currentValue;
	}

	@Override
	public long countReports() {
		return data.get().reports;
	}

	@Override
	public BootstrappingDecayingRunningAverage clone() {
		return new BootstrappingDecayingRunningAverage(this);
	}

	/**
	 * Change <code>maxReports</code>.
	 *
	 * @param maxReports
	 */
	public void changeMaxReports(int maxReports) {
		data.updateAndGet(data -> data.withMaxReports(maxReports));
	}

	/**
	 * Reports a new value while allowing for normalization of the resulting <code>currentValue</code>.
	 * Used by {@link DecayingKeyspaceAverage} to normalize the stored averages. Calling this function
	 * may (purposefully) destroy the utility of the average being kept.
	 */
	void report(UnaryOperator<Data> updateFunction) {
		data.updateAndGet(updateFunction);
	}

	double valueIfReported(UnaryOperator<Data> updateFunction) {
		return updateFunction.apply(data.get()).currentValue;
	}

	/**
	 * Export this object as {@link SimpleFieldSet}.
	 * 
	 * @param shortLived
         * 		See {@link SimpleFieldSet#SimpleFieldSet(boolean)}.
         * @return
	 */
	public SimpleFieldSet exportFieldSet(boolean shortLived) {
		Data data = this.data.get();
		SimpleFieldSet fs = new SimpleFieldSet(shortLived);
		fs.putSingle("Type", "BootstrappingDecayingRunningAverage");
		fs.put("CurrentValue", data.currentValue);
		fs.put("Reports", data.reports);
		return fs;
	}

	private boolean isInvalid(double d) {
		return d < min || d > max || Double.isInfinite(d) || Double.isNaN(d);
	}

	class Data {
		private final long maxReports;
		private final long reports;
		private final double currentValue;

		private Data(long maxReports, long reports, double currentValue) {
			this.maxReports = maxReports;
			this.reports = reports;
			this.currentValue = currentValue;
		}

		private Data(Data other) {
			this.maxReports = other.maxReports;
			this.reports = other.reports;
			this.currentValue = other.currentValue;
		}

		double currentValue() {
			return currentValue;
		}

		Data updated(double d) {
			if (isInvalid(d)) {
				return this;
			}
			double decayFactor = 1d / Math.min(reports + 1, maxReports);
			double newValue = (d * decayFactor) + (currentValue * (1 - decayFactor));
			return new Data(maxReports, reports + 1, newValue);
		}

		Data withCurrentValue(double currentValue) {
			if (isInvalid(currentValue)) {
				return this;
			}
			return new Data(maxReports, reports, currentValue);
		}

		Data withMaxReports(long maxReports) {
			return new Data(maxReports, reports, currentValue);
		}
	}
}
