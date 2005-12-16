package freenet.node;

import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.KeyBlock;

/**
 * @author amphibian
 *
 * Simple client interface... fetch and push single CHKs. No
 * splitfile decoding, no DBRs, no SSKs, for now.
 * 
 * We can build higher layers on top of this.
 */
public interface SimpleLowLevelClient {

    /**
     * Fetch a key. Throws if it cannot fetch it.
     * @param cache If false, don't cache the data. See the comments at the top
     * of Node.java.
     */
    public ClientKeyBlock getKey(ClientKey key, boolean localOnly, RequestStarterClient client, boolean cache) throws LowLevelGetException;

    /**
     * Insert a key.
     * @param cache If false, don't cache the data. See the comments at the top
     * of Node.java.
     */
    public void putCHK(ClientCHKBlock key, RequestStarterClient sctx, boolean cache) throws LowLevelPutException;
}
