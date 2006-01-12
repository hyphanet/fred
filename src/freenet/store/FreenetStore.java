package freenet.store;

import java.io.IOException;

import freenet.crypt.DSAPublicKey;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;

/**
 * Datastore interface
 */
public interface FreenetStore {

    /**
     * Retrieve a block.
     * @param dontPromote If true, don't promote data if fetched.
     * @return null if there is no such block stored, otherwise the block.
     */
    public CHKBlock fetch(NodeCHK key, boolean dontPromote) throws IOException;

    /**
     * Retrieve a block.
     * @param dontPromote If true, don't promote data if fetched.
     * @return null if there is no such block stored, otherwise the block.
     */
    public SSKBlock fetch(NodeSSK key, boolean dontPromote) throws IOException;

    /**
     * Fetch a public key.
     */
    public DSAPublicKey fetchPubKey(byte[] hash, boolean dontPromote) throws IOException;
    
    /**
     * Store a block.
     */
    public void put(KeyBlock block) throws IOException;
    
    /**
     * Store a public key.
     */
    public void put(byte[] hash, DSAPublicKey key) throws IOException;
}
