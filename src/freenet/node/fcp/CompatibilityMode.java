package freenet.node.fcp;

import java.util.Arrays;

import freenet.client.InsertContext;
import freenet.client.events.SplitfileCompatibilityModeEvent;
import freenet.support.Logger;

public class CompatibilityMode extends FCPMessage {
	
	final String identifier;
	long min;
	long max;
	final boolean global;
	byte[] cryptoKey;
	boolean dontCompress;
	boolean definitive;
	
	CompatibilityMode(String id, boolean global, long min, long max, byte[] cryptoKey, boolean dontCompress, boolean definitive) {
		this.identifier = id;
		this.global = global;
		this.min = min;
		this.max = max;
		this.cryptoKey = cryptoKey;
		this.dontCompress = dontCompress;
		this.definitive = definitive;
	}
	
	void merge(long min, long max, byte[] cryptoKey, boolean dontCompress, boolean definitive) {
		if(this.definitive) {
			Logger.warning(this, "merge() after definitive", new Exception("debug"));
			return;
		}
		if(definitive) this.definitive = true;
		if(!dontCompress) this.dontCompress = false;
		if(min > this.min) this.min = min;
		if(max < this.max || this.max == InsertContext.CompatibilityMode.COMPAT_UNKNOWN.ordinal()) this.max = max;
		if(this.cryptoKey == null) {
			this.cryptoKey = cryptoKey;
		} else if(cryptoKey != null && !Arrays.equals(this.cryptoKey, cryptoKey)) {
			Logger.error(this, "Two different crypto keys!");
			this.cryptoKey = null;
		}
	}
	
	public InsertContext.CompatibilityMode[] getModes() {
		return new InsertContext.CompatibilityMode[] {
				InsertContext.CompatibilityMode.values()[(int)min],
				InsertContext.CompatibilityMode.values()[(int)max]
		};
	}

    public SplitfileCompatibilityModeEvent toEvent() {
        InsertContext.CompatibilityMode minMode = InsertContext.CompatibilityMode.values()[(int)min];
        InsertContext.CompatibilityMode maxMode = InsertContext.CompatibilityMode.values()[(int)max];
        return new SplitfileCompatibilityModeEvent(minMode, maxMode, cryptoKey, dontCompress, definitive);
    }

}
