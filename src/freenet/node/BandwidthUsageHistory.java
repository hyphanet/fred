package freenet.node;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Date;
import java.util.NoSuchElementException;

/**
 * A fixed-size list of bandwidth usage measurements. Storing a measurement
 * automatically stores the time when it was added.
 * 
 * @author xor
 * 
 */
public class BandwidthUsageHistory implements Iterable<BandwidthUsageHistory.BandwidthUsageSample> {

	public class BandwidthUsageSample {
		private float value;
		private long time;

		public BandwidthUsageSample(float newValue) {
			value = newValue;
			time = System.currentTimeMillis();
		}

		public float getValue() {
			return (value);
		}

		public float setValue(float newValue) {
			time = System.currentTimeMillis();
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
	
	public BandwidthUsageHistory(BandwidthUsageSample[] newData) {
		if(newData == null)
			throw new IllegalArgumentException("newData == null");
		
		data = newData;
		slot = 0;
		long oldestTime = Long.MAX_VALUE;

		/* Find the oldest slot and set slot to its index so that new values 
		 * will be put into the right slots. */
		for(int idx = 0; idx < data.length; ++idx) {
			if(data[idx] != null && data[idx].getTime() < oldestTime ) {
				slot = idx;
			}
		}
	}
	
	public int getSampleCount() {
		return data.length;
	}
	
	public void putValue(float value) {
		slot = (slot+1) % data.length;
		
		if(data[slot] == null)
			data[slot] = new BandwidthUsageSample(value);
		else
			data[slot].setValue(value);
	}
	
	public BandwidthUsageSample getSample(int idx) {
		/* It should not be necessary for clients of this class to use  
		 * values of idx greater than data.length. */
		assert (idx >= 0 && idx < data.length);
		return (data[(slot+idx) % data.length]);
	}
	
	public float getAverage() {
		float sum = 0.0f;
		int count = 0;
		for(int idx = 0; idx < data.length; ++idx) {
			if(data[slot] != null) {
				sum += data[slot].getValue();
				++count;
			}
		}
		return (count != 0 ? (sum/count) : 0.0f);
	}
	
	/**
	 * Calculates a new <code>BandwidthUsageHistory</code> with a smaller amount of samples.
	 * Each sample in the new <code>BandwidthUsageHistory</code> will be calculated as an
	 * average value over <code>this.getSampleCount() / numberOfSamples</code> samples.
	 * If <code>numberOfSamples</code> does not divide <code>this.getSampleCount()</code> then
	 * the oldest remaining samples from this object will not be included in the calculation.
	 *  
	 * @param numberOfSamples The number of samples which the new <code>BandwidthUsageHistory</code> should have.
	 * @return The new <code>BandwidthUsageHistory</code>.
	 */
	public BandwidthUsageHistory getHistoryWithReducedSampleAmount(int numberOfSamples) {
		if(numberOfSamples > data.length)
			throw new IllegalArgumentException("numberOfSamples > this.data.length");
		
		BandwidthUsageSample[] newData = new BandwidthUsageSample[numberOfSamples];
		
		float value;
		long startTime;
		long endTime;
		int samplesPerValue = data.length / numberOfSamples;
		int cnt = 0;
		
		for(int idx = data.length % numberOfSamples; idx < data.length && data[idx] != null; idx++) {
			// time for a coffee, i'll finish that later :)
		}
		
		return new BandwidthUsageHistory(newData);
	}

	public Iterator<BandwidthUsageSample> iterator() {
		return new Iterator<BandwidthUsageSample>() {
			int idx = 0;
			
			public boolean hasNext() {
				return (idx != data.length);
			}

			public BandwidthUsageSample next() {
				if(!hasNext())
					throw new NoSuchElementException();
				
				/* We do not clone() it to prevent generation of insane amounts of objects,
				 * the client classes are trusted not to damage the referenced objects they receive */
				BandwidthUsageSample result = data[(slot+idx) % data.length];
				++idx;
				return result;
			}

			/**
			 * This cannot be used: The BandwidthUsageHistory contains a fixed
			 * amount of elements.
			 */
			public void remove() {
				throw new UnsupportedOperationException();
			}
			
		};
	}
}
