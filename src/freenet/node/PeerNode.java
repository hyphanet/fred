package freenet.node;

import freenet.io.comm.Peer;
import freenet.io.comm.UdpSocketManager;

/**
 * A PeerNode.
 * This can either represent a node we have full information on 
 * (a LivePeerNode), or a node we are currently negotiating with
 * which we may know very little about (a ConnectingPeerNode).
 */
public abstract class PeerNode {
    final UdpSocketManager usm;
    Peer peer;
    final PeerManager pm;

    PeerNode(UdpSocketManager usm, Peer peer, PeerManager pm) {
        this.usm = usm;
        this.peer = peer;
        this.pm = pm;
    }

    public abstract boolean isConnected();
    
    /**
     * Get the raw Peer, if possible. For some transports this
     * may return null.
     * @return The transport-level Peer used by this node.
     */
    Peer getRawPeer() {
        return peer;
    }

    /**
     * Process a packet that appears to have come from this node.
     * @param buf The buffer to read from.
     * @param offset The offset to start reading at.
     * @param length The number of bytes to read.
     * @param peer2 The peer the message was addressed to.
     * @return True if we accept this packet as belonging to us,
     * false if we think it may belong to another node.
     */
    public abstract boolean process(byte[] buf, int offset, 
            int length, Peer peer2);
}
