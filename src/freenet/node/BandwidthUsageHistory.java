/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A fixed-size list of bandwidth usage measurements.
 * 
 * @author xor
 * 
 */
public class BandwidthUsageHistory implements Iterable<BandwidthUsageHistory.BandwidthUsageSample> {

	public static class BandwidthUsageSample {
		private float value;
		private long time;

		public BandwidthUsageSample(float newValue, long newTime) {
			value = newValue;
			time = newTime;
		}

		public float getValue() {
			return (value);
		}

		public float setValue(float newValue, long newTime) {
			time = newTime;
			return (value = newValue);
		}

		public long getTime() {
			return (time);
		}
	}

	protected final BandwidthUsageSample[] data;
	protected int slot;

	public BandwidthUsageHistory(int numberOfSamples) {
		data = new BandwidthUsageSample[numberOfSamples];
		slot = 0;
	}

	/**
	 * Create a new BandWidthUsageHistory from an existing array of BandwidthUsageSample. 
	 * @param newData The data for the new object.
	 * @param bNextSlotIsNotIndex0 If set to false, the new object assumes that slot 0 is the oldest sample. If set to true, the next slot is searched (time expensive!).
	 */
	protected BandwidthUsageHistory(BandwidthUsageSample[] newData, boolean bNextSlotIsNotIndex0) {
		if (newData == null)
			throw new IllegalArgumentException("newData == null");

		data = newData;

		/* TODO: Remove if it is not needed by any caller. */
		if(bNextSlotIsNotIndex0)
			slot = getNextFreeSlot();
		else {
			slot = 0;
			assert(getNextFreeSlot() == slot);	/* Catch wrong use of this constructor. */
		}
	}
	
	protected BandwidthUsageHistory(BandwidthUsageSample[] newData, int newSlot) {
		if (newData == null)
			throw new IllegalArgumentException("newData == null");
		if (newSlot < 0 || newSlot >= newData.length)
			throw new IllegalArgumentException("newSlot invalid: " + newSlot);

		data = newData;
		slot = newSlot;
		
		assert (getNextFreeSlot() == slot); /* Catch wrong use of this constructor. */
	}

	public int getSampleCount() {
		return(data.length);
	}
	
	/**
	 * Creates a new BandwidthUsageHistory with the same sample data but different space for samples.
	 * @param newSampleCount The amount of samples for the new object. If <code>newSampleCount</code> is less than the current sample count, the oldest samples are dropped.
	 */
	public synchronized BandwidthUsageHistory clone(int newSampleCount) {
		if(newSampleCount < 1)
			throw new IllegalArgumentException("newSampleCount < 1");
		
		BandwidthUsageSample[] newData = new BandwidthUsageSample[newSampleCount];
		int newIdx = 0;
		
		for(int idx = newSampleCount >= data.length ? 0 : (data.length - newSampleCount); idx < data.length; idx++) {
			newData[newIdx++] = getSample(idx);
		}
		newIdx %= newData.length;
		
		return (new BandwidthUsageHistory(newData, newIdx));
	}

	public synchronized void putValue(float value, long time) {
		slot = (slot + 1) % data.length;

		if (data[slot] == null)
			data[slot] = new BandwidthUsageSample(value, time);
		else
			data[slot].setValue(value, time);
	}

	/**
	 * Returns the <code>BandwidthUsageSample</code> with index <code>idx</code> from this object. The index is zero based, index 0 being the
	 * oldest bandwidth sample.
	 * Do not modify it, the original object is returned instead of a copy to prevent creation of large amounts of BandwidthUsageSample-objects.
	 * @param idx The index of the sample, index 0 being the oldest bandwidth sample.
	 * @return The BandwidthUsageSample with the desired index, null if there is no such element yet.
	 */
	public synchronized BandwidthUsageSample getSample(int idx) {
		/* It should not be necessary for clients of this class to use values of idx greater than data.length, it will work however. */
		assert (idx >= 0 && idx < data.length);
		return(data[(slot + idx) % data.length]);
	}

	public synchronized float getAverage() {
		float sum = 0.0f;
		int count = 0;
		for (int idx = 0; idx < data.length; ++idx) {
			if (data[slot] != null) {
				sum += data[slot].getValue();
				++count;
			}
		}
		return(count != 0 ? (sum / count) : 0.0f);
	}

	/**
	 * Calculates a new <code>BandwidthUsageHistory</code> with a smaller amount of samples. Each sample in the new
	 * <code>BandwidthUsageHistory</code> will be calculated as an average value over <code>this.getSampleCount() / numberOfSamples</code>
	 * samples. If <code>numberOfSamples</code> does not divide <code>this.getSampleCount()</code> then the oldest remaining samples from this
	 * object will not be included in the calculation.
	 * 
	 * @param numberOfSamples
	 *            The number of samples which the new
	 *            <code>BandwidthUsageHistory</code> should have.
	 * @return The new <code>BandwidthUsageHistory</code>.
	 */
	public synchronized BandwidthUsageHistory getHistoryWithReducedSampleAmount(int numberOfSamples) {
		if (numberOfSamples > data.length)
			throw new IllegalArgumentException("numberOfSamples > this.data.length");

		BandwidthUsageSample[] newData = new BandwidthUsageSample[numberOfSamples];
		int samplesPerValue = data.length / numberOfSamples;
		
		float value = 0.0f;
		long startTime = -1; 
		int cnt = 0;
		int newIdx = 0;

		/* Start at data.length % numberOfSamples to drop the oldest remaining samples, see JavaDoc of this function */ 
		for (int idx = data.length % numberOfSamples; idx < data.length && data[idx] != null; ++idx) {
			BandwidthUsageSample s = getSample(idx);
			value += s.getValue();
			if(startTime < 0)
				startTime = s.getTime();
		
			if(++cnt == samplesPerValue) {
				long endTime = s.getTime();
				assert(newIdx < newData.length); /* If this loop is constructed correctly we do not have to check newIdx. */ 
				newData[newIdx++] = new BandwidthUsageSample(value/samplesPerValue, startTime + (endTime-startTime) / 2);
				cnt = 0;
				startTime = -1;
			}
		}

		return(new BandwidthUsageHistory(newData, false));
	}

	/**
	 * You HAVE TO use <code>synchronized(){}</code> on the BandwidthUsageHistory object when you use iterator()!
	 */
	public Iterator<BandwidthUsageSample> iterator() {
		return new Iterator<BandwidthUsageSample>() {
			int idx = 0;

			public boolean hasNext() {
				return(idx != data.length);
			}

			/**
			 * Returns the next BandwidthUsageSample from this object. Do not modify it, the original object is returned instead of a copy to
			 * prevent creation of large amounts of BandwidthUsageSample-objects.
			 */
			public BandwidthUsageSample next() {
				if (!hasNext())
					throw new NoSuchElementException();

				BandwidthUsageSample result = getSample(idx);
				++idx;
				return(result);
			}

			/**
			 * This cannot be used: The BandwidthUsageHistory contains a fixed amount of elements.
			 */
			public void remove() {
				throw new UnsupportedOperationException();
			}

		};
	}
	
	/**
	 * Uses O(data.length) time! Should be avoided by passing over calculated values to constructors!
	 * Should be used in assert() statements.
	 * @return The index of the oldest sample in the data if the array was full, the next empty slot otherwise.
	 */
	protected int getNextFreeSlot() {
		int oldest = 0;
		long oldestTime = Long.MAX_VALUE;

		for (int idx = 0; idx < data.length; ++idx) {
			if (data[idx] == null) {
				oldest = idx;
				break;
			}
			else if (data[idx] != null && data[idx].getTime() < oldestTime) {
				oldest = idx;
			}
		}
		
		return(oldest);
	}
}
