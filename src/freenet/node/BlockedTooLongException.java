package freenet.node;

import freenet.node.transport.PacketTracker;

public class BlockedTooLongException extends Exception {
	private static final long serialVersionUID = 1L;

	public final PacketTracker tracker;
	public final long delta;
	
	public BlockedTooLongException(PacketTracker tracker, long delta) {
		this.tracker = tracker;
		this.delta = delta;
	}

}
