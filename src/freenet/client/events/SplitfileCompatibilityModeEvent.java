package freenet.client.events;

public class SplitfileCompatibilityModeEvent implements ClientEvent {

	public final long minCompatibilityMode;
	public final long maxCompatibilityMode;
	
	public final static int CODE = 0x0D;
	
	public int getCode() {
		return CODE;
	}

	public String getDescription() {
		if(minCompatibilityMode == -1)
			return "Unknown CompatibilityMode";
		else
			return "CompatibilityMode between "+minCompatibilityMode+" and "+maxCompatibilityMode;
	}
	
	public SplitfileCompatibilityModeEvent(long min, long max) {
		this.minCompatibilityMode = min;
		this.maxCompatibilityMode = max;
	}
	
}
