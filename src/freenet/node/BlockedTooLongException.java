package freenet.node;

public class BlockedTooLongException extends Exception {

	public final PacketTracker tracker;
	public final long delta;
	
	public BlockedTooLongException(PacketTracker tracker, long delta) {
		this.tracker = tracker;
		this.delta = delta;
	}

}
