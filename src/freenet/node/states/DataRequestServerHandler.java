package freenet.node.states;

import freenet.keys.NodeCHK;

/**
 * Server-node handler.
 * Does the actual fetching of data from the network.
 * Not specific to any single server node despite the name.
 * Used by internal clients as well as to fetch data to be
 * sent to an external requestor.
 */
public class DataRequestServerHandler implements Runnable {

    final long id;
    final NodeCHK key;
    int htl;
    
    
    /**
     * Create a DRSH to fetch a key.
     * @param id The unique ID of the request chain.
     * @param htl The initial hops-to-live.
     * @param key The key to fetch.
     */
    public DataRequestServerHandler(long id, int htl, NodeCHK key) {
        this.id = id;
        this.key = key;
        this.htl = htl;
        // TODO Auto-generated constructor stub
    }

    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    public void run() {
        // TODO Auto-generated method stub
        
    }

    /**
     * @return The current HTL.
     */
    public int getHTL() {
        return htl;
    }

    /**
     * @return Whether we are actually transferring data right now.
     * True if we have received the DataReply, or we have started to
     * receive the actual data.
     */
    public boolean isTransferring() {
        // TODO Auto-generated method stub
        return false;
    }

}
