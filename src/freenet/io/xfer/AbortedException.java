package freenet.io.xfer;

/**
 * Thrown when a transfer is aborted, and caller tries to do something on PRB,
 * in order to avoid some races.
 */
public class AbortedException extends Exception {

	public AbortedException(String msg) {
		super(msg);
	}

}
