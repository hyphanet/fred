package freenet.crypt;

public class DecryptionFailedException extends Exception {
	private static final long serialVersionUID = -1;
    public DecryptionFailedException (String m) {
	super(m);
    }
}
