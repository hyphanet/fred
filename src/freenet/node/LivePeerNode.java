package freenet.node;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.Peer;
import freenet.io.comm.UdpSocketManager;
import freenet.node.rt.*;

/**
 * A peer node which we have full identity details for. Either
 * from seednodes, or we have completed connection setup at least
 * once.
 */
public class LivePeerNode extends PeerNode {

    boolean isConnected;
    final RoutingData routingData;
    
    LivePeerNode(UdpSocketManager usm, PeerManager pm, Peer peer, RoutingData routingData) {
        super(usm, peer, pm);
        this.routingData = routingData;
    }
    
    /** Have we completed connection negotiations successfully? */
    public boolean isConnected() {
        return isConnected;
    }
    
    /** Return routing data */
    RoutingData getRoutingData() {
        return routingData;
    }
    
    /** Send a message. Will feed to the UdpSocketManager, possibly
     * queueing, encrypting or padding first.
     * @param m Dijjer-level message to send.
     */
    void sendMessage(Message m) {
        // FIXME: encrypt etc
        usm.send(getRawPeer(), m);
    }

    /**
     * Decrypt and process the packet.
     */
    public boolean process(byte[] buf, int offset, int length, Peer peer2) {
        // FIXME: do actual crypto :)
        // FIXME: authenticate
        // Do NOT include peer2 in the Message. We MUST NOT try to match
        // on source. We MUST match on SOURCE_PEER, because that is
        // authenticated.
        Message m = Message.decodeFromPacket(buf, offset, length, null);
        m.set(DMT.SOURCE_PEER, this);
        usm.checkFilters(m);
        return true;
    }
}
