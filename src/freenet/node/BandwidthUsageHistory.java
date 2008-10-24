package freenet.node;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Date;

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
			int idx = (slot  - data.length) % data.length;
			
			public boolean hasNext() {
				// TODO Auto-generated method stub
				return false;
			}

			public BandwidthUsageSample next() {
				// TODO Auto-generated method stub
				return null;
			}

			public void remove() {
				// TODO Auto-generated method stub
				
			}
			
		};
	}
}
