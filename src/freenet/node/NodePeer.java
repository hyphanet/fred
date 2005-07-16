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
}
