package freenet.node;

import freenet.node.states.DataRequestServerHandler;

/**
 * A message chain.
 */
public class Chain {
    final long id;
    final DataRequestServerHandler fetcher;
    final PeerNode source;

    public Chain(long id2, DataRequestServerHandler sh, PeerNode source) {
        id = id2;
        fetcher = sh;
        this.source = source;
    }
    
    /**
     * @return The DataRequestServerHandler currently fetching for
     * this chain.
     */
    public DataRequestServerHandler getServerHandler() {
        return fetcher;
    }
    
    public PeerNode getSource() {
        return source;
    }
}
