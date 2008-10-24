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

		public BandwidthUsageSample() {
			value = 0.0f;
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
			return time;
		}
	}

	protected final BandwidthUsageSample[] data;
	protected int slot;

	public BandwidthUsageHistory(int numberOfSamples) {
		data = new BandwidthUsageSample[numberOfSamples];
		for(int idx = 0; idx < numberOfSamples; ++idx) {
			data[idx] = new BandwidthUsageSample();
		}
		slot = 0;
	}
	
	public void putValue(float value) {
		slot = (slot+1) % data.length;
		
		data[slot].setValue(value);
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
				
				// FIXME: figure out whether we should clone() it.
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
