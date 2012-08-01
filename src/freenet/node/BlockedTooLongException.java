package freenet.node;

public class BlockedTooLongException extends Exception {
	private static final long serialVersionUID = 1L;

	public final long delta;
	
	public BlockedTooLongException(long delta) {
		this.delta = delta;
	}

}
