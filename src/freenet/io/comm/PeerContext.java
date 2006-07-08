package freenet.io.comm;

/**
 * @author amphibian
 * 
 * Everything that is needed to send a message, including the Peer.
 * Implemented by PeerNode, for example.
 */
public interface PeerContext {
    // Largely opaque interface for now
    Peer getPeer();

    /** Force the peer to disconnect */
	void forceDisconnect();

	/** Is the peer connected? If we can't tell, return true. */
	boolean isConnected();
}
