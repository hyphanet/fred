package freenet.client.events;

import freenet.client.InsertContext.CompatibilityMode;

public class SplitfileCompatibilityModeEvent implements ClientEvent {

	public final CompatibilityMode minCompatibilityMode;
	public final CompatibilityMode maxCompatibilityMode;
	public final byte[] splitfileCryptoKey;
	public final boolean dontCompress;
	public final boolean bottomLayer;
	
	public final static int CODE = 0x0D;
	
	@Override
	public int getCode() {
		return CODE;
	}

	@Override
	public String getDescription() {
	    return "CompatibilityMode between "+minCompatibilityMode+" and "+maxCompatibilityMode;
	}
	
	public SplitfileCompatibilityModeEvent(CompatibilityMode min, CompatibilityMode max, byte[] splitfileCryptoKey, boolean dontCompress, boolean bottomLayer) {
		this.minCompatibilityMode = min;
		this.maxCompatibilityMode = max;
		this.splitfileCryptoKey = splitfileCryptoKey;
		this.dontCompress = dontCompress;
		this.bottomLayer = bottomLayer;
	}
	
}
