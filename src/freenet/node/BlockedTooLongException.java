package freenet.node;

public class BlockedTooLongException extends Exception {

	public final KeyTracker tracker;
	public final long delta;
	
	public BlockedTooLongException(KeyTracker tracker, long delta) {
		this.tracker = tracker;
		this.delta = delta;
	}

}
