
package freenet.support;

import freenet.support.math.RunningAverage;
import freenet.support.math.TrivialRunningAverage;

/**
 * Similiar to a standard histogram, but each bar is reasoned independently.
 * Pretty much just an array of running averages.
 * Used for tracking success rates per-location.
 */
public class Histogram2 {

	private final double MAX;
	private final RunningAverage[] bars;

	public Histogram2(final int numBars, final double maxValue) {
		this.MAX=maxValue;
		this.bars=new RunningAverage[numBars];
		for (int i=0; i<numBars; i++) {
			this.bars[i]=new TrivialRunningAverage();
		}
	}

	public void report(final double key, final double value) {
		if (key<0.0 || key>=MAX) return;
		int n=(int)(bars.length*key/MAX);
		bars[n].report(value);
	}

	public int[] getPercentageArray(int localMax) {
		int[] retval=new int[bars.length];
		for (int i=0; i<retval.length; i++) {
			int val=(int)(bars[i].currentValue()*localMax/MAX);
			retval[i]=val;
		}
		return retval;
	}
}
