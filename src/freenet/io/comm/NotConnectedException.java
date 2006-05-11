package freenet.io.comm;

/**
 * @author amphibian
 * 
 * Exception thrown when we try to send a message to a node that is
 * not currently connected.
 */
public class NotConnectedException extends Exception {
	private static final long serialVersionUID = -1;
    public NotConnectedException(String string) {
        super(string);
    }

    public NotConnectedException() {
        super();
    }

}
