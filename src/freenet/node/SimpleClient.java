package freenet.node;

import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientPublishStreamKey;

/**
 * @author amphibian
 *
 * Simple client interface... fetch and push single CHKs. No
 * splitfile decoding, no DBRs, no SSKs, for now.
 * 
 * We can build higher layers on top of this.
 */
public interface SimpleClient {

    /**
     * Fetch a key. Return null if cannot retrieve it.
     */
    public ClientCHKBlock getCHK(ClientCHK key);

    /**
     * Insert a key.
     */
    public void putCHK(ClientCHKBlock key);
}
