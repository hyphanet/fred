package freenet.io.comm;

/**
 * Thown when we can't parse a string to a Peer.
 * @author amphibian
 */
public class ReferenceSignatureVerificationException extends Exception {
	private static final long serialVersionUID = -1;
    public ReferenceSignatureVerificationException(Exception e) {
        super(e);
    }

    public ReferenceSignatureVerificationException() {
        super();
    }

	public ReferenceSignatureVerificationException(String string) {
		super(string);
	}

}
