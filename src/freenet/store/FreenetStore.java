package freenet.store;

import java.io.IOException;

import freenet.keys.CHKBlock;
import freenet.keys.NodeCHK;

/**
 * Datastore interface
 */
public interface FreenetStore {

    /**
     * Retrieve a block.
     * @return null if there is no such block stored, otherwise the block.
     */
    public CHKBlock fetch(NodeCHK chk) throws IOException;

    /**
     * Store a block.
     */
    public void put(CHKBlock block) throws IOException;
}