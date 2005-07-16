package freenet.node;

import java.util.HashMap;

import freenet.crypt.RandomSource;
import freenet.io.comm.Peer;

/**
 * @author amphibian
 * 
 * Another node.
 */
public class NodePeer {
    
    NodePeer(Location loc, Peer contact) {
        currentLocation = loc;
        peer = contact;
    }
    
    /** Keyspace location */
    private Location currentLocation;
    
    /** Contact information - FIXME should be a NodeReference??? */
    private Peer peer;
    
    /**
     * Get the current Location, which represents our current 
     * specialization in the keyspace.
     */
    public Location getLocation() {
        return currentLocation;
    }

    /**
     * Get the Peer, the underlying TCP/IP address that UdpSocketManager
     * can understand.
     * @return
     */
    public Peer getPeer() {
        return peer;
    }

    /*
     * Stuff related to packet retransmission etc.
     * 
     * We need:
     * - A list of the message content of the last 256 packets we
     *   have sent. Every time we send a packet, we add to the
     *   end of the list. Every time we get an ack, we remove the
     *   relevant packet. Every time we get a retransmit request,
     *   we resend that packet. If packet N-255 has not yet been
     *   acked, we do not allow packet N to be sent.
     * - A function to determine whether packet N-255 has been
     *   acked, and a mutex to block on, in the prepare-to-send
     *   function.
     * - A list of packets that need to be ACKed.
     * - A list of packets that need to be resent by the other side.
     * - A list of packets that this side needs to resend.
     * - A function to determine the next time at which we need to
     *   check whether we need to send an empty packet just for the
     *   acks and resend requests.
     * - A thread to resend packets that were requested.
     * - A thread to send a packet with only acks and resend
     *   requests, should it be necessary (i.e. if they are older
     *   than 200ms).
     * 
     * For now, we don't support dropping messages in response to
     * OOM. But if we get a notification of a dropped message we
     * will stop trying to get it resent.
     */
    
    
    
    /**
     * @param seqNumber
     */
    public void receivedPacket(int seqNumber) {
        // FIXME: do something!
    }
}
