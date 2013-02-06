package freenet.support;

/** Tracks when a condition was last true, e.g. whether a peer is connected. Self-synchronized. */
public class BooleanLastTrueTracker {
	
	private boolean isConnected;
	private long timeLastConnected;
	
	public BooleanLastTrueTracker() {
		isConnected = false;
		timeLastConnected = -1;
	}

	/** Initialise with a time last connected */
	public BooleanLastTrueTracker(long lastConnected) {
		isConnected = false;
		timeLastConnected = lastConnected;
	}

	public synchronized boolean isConnected() {
		return isConnected;
	}
	
	public synchronized boolean setConnected(boolean value, long now) {
		if(value == isConnected) return value;
		if(!isConnected)
			timeLastConnected = now;
		isConnected = value;
		return !value;
	}
	
	public synchronized long getTimeLastConnected(long now) {
		if(isConnected) return now;
		else return timeLastConnected;
	}

}
