package freenet.node.rt;

import freenet.node.PeerNode;

/**
 * Object returned by a Router, which is essentially an ordered
 * list of PeerNode's to route to.
 */
public interface Routing {
    
    /** 
     * Get the next node on the list.
     * @return The next best choice for a node to route to.
     */
    PeerNode getNextNode();
    
}
