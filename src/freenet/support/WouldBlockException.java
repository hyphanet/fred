package freenet.support;

import freenet.io.comm.LowLevelFilterException;

/**
 * Thrown when we would have to block but have been told not to.
 */
public class WouldBlockException extends LowLevelFilterException {
	private static final long serialVersionUID = -1;

	public WouldBlockException(String string) {
		super(string);
	}

	public WouldBlockException() {
		super();
	}

}
