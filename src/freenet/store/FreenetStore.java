package freenet.store;

import java.io.IOException;

import com.sleepycat.je.DatabaseException;

/**
 * Datastore interface
 */
public interface FreenetStore {

	public static final short TYPE_CHK = 0;
	public static final short TYPE_PUBKEY = 1;
	public static final short TYPE_SSK = 2;

	/**
	 * Retrieve a block. Use the StoreCallback to convert it to the appropriate type of block.
	 * @param routingKey The routing key i.e. the database key under which the block is stored.
	 * @param dontPromote If true, don't promote the block to the top of the LRU.
	 * @return A StorableBlock, or null if the key cannot be found.
	 * @throws IOException If a disk I/O error occurs.
	 */
	StorableBlock fetch(byte[] routingKey, byte[] fullKey, boolean dontPromote) throws IOException;
	
	/**
	 * Store a block.
	 * 
	 * @throws KeyCollisionException
	 *             If the key already exists and <code>callback.collisionPossible()</code> is
	 *             <code>true</code>.
	 * @param overwrite
	 *            If true, overwrite old content rather than throwing a
	 *            <code>KeyCollisionException</code>.
	 */
    public void put(StorableBlock block, byte[] routingkey, byte[] fullKey, byte[] data, byte[] header, 
    		boolean overwrite) throws IOException, KeyCollisionException;
    
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

	public long getBloomFalsePositive();
	
	/**
	 * Check if a routing key probably
	 * 
	 * @param routingkey
	 * @return <code>false</code> <b>only</b> if the key does not exist in store.
	 */
	public boolean probablyInStore(byte[] routingKey);
}
