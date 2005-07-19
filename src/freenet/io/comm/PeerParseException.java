package freenet.io.comm;

/**
 * Thown when we can't parse a string to a Peer.
 * @author amphibian
 */
public class PeerParseException extends Exception {

    public PeerParseException(Exception e) {
        super(e);
    }

    public PeerParseException() {
        super();
    }

}
