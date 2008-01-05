package freenet.store;

import java.io.IOException;

import com.sleepycat.je.DatabaseException;

import freenet.crypt.DSAPublicKey;
import freenet.keys.CHKBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;

/**
 * Datastore interface
 */
public interface FreenetStore {

	/**
	 * Retrieve a block. Use the StoreCallback to convert it to the appropriate type of block.
	 * @param routingKey The routing key i.e. the database key under which the block is stored.
	 * @param dontPromote If true, don't promote the block to the top of the LRU.
	 * @return A StorableBlock, or null if the key cannot be found.
	 * @throws IOException If a disk I/O error occurs.
	 */
	StorableBlock fetch(byte[] routingKey, byte[] fullKey, boolean dontPromote) throws IOException;
	
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
    
    /** Store a block.
     * @throws KeyCollisionException If the key already exists but has different contents.
     * @param ignoreAndOverwrite If true, overwrite old content rather than throwing a KeyCollisionException.
     */
    public void put(StorableBlock block, byte[] routingkey, byte[] fullKey, byte[] data, byte[] header, 
    		boolean overwrite) throws IOException, KeyCollisionException;
    
    /**
     * Store a block.
     * @throws KeyCollisionException If the key already exists but has different contents.
     * @param ignoreAndOverwrite If true, overwrite old content rather than throwing a KeyCollisionException. 
     */
    public void put(SSKBlock block, boolean ignoreAndOverwrite) throws IOException, KeyCollisionException;

    /**
     * Store a block.
     */
    public void put(CHKBlock block) throws IOException;
    
    /**
     * Store a public key.
     */
    public void put(byte[] hash, DSAPublicKey key) throws IOException;

    /**
     * Change the store size.
     * @param maxStoreKeys The maximum number of keys to be cached.
     * @param shrinkNow If false, don't shrink the store immediately.
     * @throws IOException 
     * @throws DatabaseException 
     */
	public void setMaxKeys(long maxStoreKeys, boolean shrinkNow) throws DatabaseException, IOException;
    
    public long getMaxKeys();
	
	public long hits();
	
	public long misses();
	
	public long writes();

	public long keyCount();
}
