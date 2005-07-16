package freenet.node;

import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;

/**
 * @author amphibian
 *
 * Simple client interface... fetch and push single CHKs. No
 * splitfile decoding, no DBRs, no SSKs, for now.
 * 
 * We can build higher layers on top of this.
 */
public interface SimpleClient {

    public ClientCHKBlock getCHK(ClientCHK key);
    
    public void putCHK(ClientCHKBlock key);
    
}
