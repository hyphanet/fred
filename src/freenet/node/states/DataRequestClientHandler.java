package freenet.node.states;

import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.NodeCHK;

/**
 * Client-node handler.
 * Runs on a thread, until we do continuations.
 */
public class DataRequestClientHandler implements Runnable {

    final long id;
    final NodeCHK key;
    /** The header */
    byte[] header;
    /** The actual data */
    PartiallyReceivedBlock prb;
    /** The server thread fetching the data for us, if any */
    DataRequestServerHandler server;
    
    /**
     * Create a DRCH to just send data from the store.
     * @param id The message chain unique ID.
     * @param key The key being requested.
     * @param block The actual data.
     */
    public DataRequestClientHandler(long id, NodeCHK key, CHKBlock block) {
        this.id = id;
        this.key = key;
        header = block.getHeader();
        byte[] data = block.getData();
        prb = new PartiallyReceivedBlock(32, 1024, data);
    }

    /**
     * Create a DRCH to send data from a given DRSH.
     * @param id The message chain unique ID.
     * @param key The key being requested.
     * @param server The DRSH fetching the key from the network.
     */
    public DataRequestClientHandler(long id, NodeCHK key, DataRequestServerHandler server) {
        this.id = id;
        this.key = key;
        header = null;
        prb = null;
        this.server = server;
        server.register(this);
    }

    public void run() {
        // TODO Auto-generated method stub
        
    }
    
}
