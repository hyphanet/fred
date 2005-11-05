package freenet.io.comm;

/**
 * Thrown when the node is disconnected in the middle of (or
 * at the beginning of) a waitFor(). Not the same as 
 * NotConnectedException.
 */
public class DisconnectedException extends Exception {
	static final long serialVersionUID = -1;
}
