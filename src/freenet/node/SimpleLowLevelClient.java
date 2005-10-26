package freenet.node;

import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
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
     * Fetch a key. Return null if cannot retrieve it.
     */
    public KeyBlock getKey(ClientKey key);

    /**
     * Insert a key.
     */
    public void putCHK(ClientCHKBlock key);
}
