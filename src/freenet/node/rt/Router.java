package freenet.node.rt;

import freenet.keys.Key;

/**
 * Classes implementing this interface perform the actual routing.
 * They are not usually stateless, but we want to keep the main
 * list of peers in the PeerManager object.
 */
public interface Router {

    /**
     * Route a request. Returns a Routing object.
     * @param k The key to be routed.
     * @param hopsToLive The HTL of the request.
     * @return A Routing object which will return a list of nodes
     * to send the request to. We need this because if we don't,
     * we can't deal with loops.
     */
    Routing route(Key k, int hopsToLive);

    /**
     * Return a Routing object for recycling.
     */
    void returnRouting(Routing r);
}
