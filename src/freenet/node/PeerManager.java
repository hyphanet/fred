package freenet.node;

import java.util.Hashtable;

import freenet.io.comm.LowLevelFilter;
import freenet.io.comm.Peer;
import freenet.keys.Key;
import freenet.node.rt.*;

/**
 * Keeps track of all known peers.
 * We may or may not be connected to each one.
 * Each one has some routing information.
 * And so on.
 */
public class PeerManager implements LowLevelFilter {

    PeerManager(RouterFactory r) {
        this.router = r.newRouter(this);
        peerNodesByPeer = new Hashtable();
    }
    
    /** Class which routes requests, using the data stored on
     * each peer. May well have its own internal structures, but
     * we try to keep the main list of peers in PeerManager.
     */
    final Router router;
    Node node;
    final Hashtable peerNodesByPeer;
    
    void setNode(Node node) {
        this.node = node;
    }
    
    
    /**
     * Route a request.
     * @param k The key to be routed.
     * @param hopsToLive The hopsToLive at which it is to be routed.
     * Will not be used by most routing algorithms but could 
     * conceivably affect things.
     * @return Routing structure which is a source of PeerNode's.
     * We don't just return the PeerNode because we can get loop
     * rejects and therefore may need to try more than one node.
     */
    public Routing route(Key k, int hopsToLive) {
        return router.route(k, hopsToLive);
    }
    
    /**
     * Return a Routing object for recycling.
     */
    public void returnRouting(Routing r) {
        router.returnRouting(r);
    }

    /**
     * Return an array of the currently connected nodes.
     * Note that this may be an internal structure, and it
     * MUST NOT BE MODIFIED by the caller.
     */
    public PeerNode[] getConnectedNodesArray() {
        // TODO Auto-generated method stub
        return null;
    }


    /**
     * Process an incoming packet. This will be encrypted. We need to:
     * - Find the PeerNode responsible for this packet.
     * - Send this packet to the PeerNode, which will decrypt and 
     * authenticate it.
     * 
     * Either:
     * a) A PeerNode wants the packet because it has a fully set up 
     * connection and wants data (in which case it will authenticate 
     * the packet, and can return false if it is not authentic) or:
     * b) A PeerNode wants the packet because it's in the middle of 
     * negotiation. In which case it will accept the packet on the 
     * basis solely of the source peer. Of course negotiation may fail.
     */
    public void process(byte[] buf, int offset, int length, Peer peer) {
        PeerNode pn = findLikelySourceNode(peer);
        if(pn != null) {
            if(pn.process(buf, offset, length, peer)) {
                // It liked it
                return;
            }
            // Just because it failed to authenticate does not
            // automatically mean a spoofing attack
        }
        // Either it failed to authenticate, or we couldn't find a peerNode.
        // Treat as new connection
        if(node.wantConnections()) {
            startIncomingNegotiations(buf, offset, length, peer);
        } // Else just ignore it
    }


    /**
     * Start incoming negotiations with a peer as a result of a packet
     * from an unknown node. Will create a ConnectingPeerNode, register it
     * and feed it the packet.
     * @param buf Buffer to read packet from.
     * @param offset Offset to start reading at.
     * @param length Length to read.
     * @param peer The Peer we got the packet from.
     */
    private void startIncomingNegotiations(byte[] buf, int offset, int length, Peer peer) {
        ConnectingPeerNode pn = new ConnectingPeerNode(node.usm, peer, this);
        peerNodesByPeer.put(peer, pn);
        pn.process(buf, offset, length, peer);
    }


    /**
     * Find a PeerNode that may want a packet from a given peer.
     * This is based on the Peer; the PeerNode will then 
     * authenticate, and may reject the packet - either in the case
     * of malicious action, or in the case of a node changing IP.
     * @param peer The Peer to search for.
     * @return A PeerNode, or null.
     */
    private PeerNode findLikelySourceNode(Peer peer) {
        return (PeerNode) peerNodesByPeer.get(peer);
    }
}
