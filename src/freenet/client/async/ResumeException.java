package freenet.client.async;

import freenet.support.io.CannotCreateFromFieldSetException;

/**
 * Thrown when the resuming of a request from a SimpleFieldSet fails. If this happens
 * then we simply restart the request from the beginning.
 */
public class ResumeException extends Exception {
	private static final long serialVersionUID = 1L;

	public ResumeException(String msg) {
		super(msg);
	}

	public ResumeException(String msg, CannotCreateFromFieldSetException e) {
		super(msg+" : "+e, e);
	}

}
