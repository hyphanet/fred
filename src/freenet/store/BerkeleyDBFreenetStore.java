package freenet.store;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;
import com.sleepycat.je.BtreeStats;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.SecondaryConfig;
import com.sleepycat.je.SecondaryDatabase;
import com.sleepycat.je.SecondaryKeyCreator;
import com.sleepycat.je.Transaction;

import freenet.crypt.DSAPublicKey;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;

/** 
 * Freenet datastore based on BerkelyDB Java Edition by sleepycat software
 * More info at http://www.sleepycat.com/products/bdbje.html
 * 
 * @author tubbie
 * 
 * TODO: Fix ugly Exception handling
 */
public class BerkeleyDBFreenetStore implements FreenetStore {

    final int dataBlockSize;
    final int headerBlockSize;
	
	private final Environment environment;
	private final TupleBinding storeBlockTupleBinding;
	private final TupleBinding longTupleBinding;
	private final File fixSecondaryFile;
	
	private long chkBlocksInStore;
	private long maxChkBlocks;
	private final Database chkDB;
	private final Database chkDB_accessTime;
	private final RandomAccessFile chkStore;
	
	private long lastRecentlyUsed;
	
	private boolean closed = false;
	
	/**
     * Initializes database
     * @param the directory where the store is located
     * @throws FileNotFoundException if the dir does not exist and could not be created
     */
	public BerkeleyDBFreenetStore(String storeDir, long maxChkBlocks, int blockSize, int headerSize) throws Exception {
		this.dataBlockSize = blockSize;
		this.headerBlockSize = headerSize;
		// Percentage of the database that must contain usefull data
		// decrease to increase performance, increase to save disk space
		System.setProperty("je.cleaner.minUtilization","98");
		
		// Delete empty log files
		System.setProperty("je.cleaner.expunge","true");
		
		// Percentage of the maximum heap size used as a cache
		System.setProperty("je.maxMemoryPercent","30");
		
		this.maxChkBlocks=maxChkBlocks;
		
		// Initialize environment
		EnvironmentConfig envConfig = new EnvironmentConfig();
		envConfig.setAllowCreate(true);
		envConfig.setTransactional(true);
		envConfig.setTxnWriteNoSync(true);
		File dir = new File(storeDir);
		if(!dir.exists())
			dir.mkdir();
		File dbDir = new File(dir,"database");
		if(!dbDir.exists())
			dbDir.mkdir();

		environment = new Environment(dbDir, envConfig);
		
		// Initialize CHK database
		DatabaseConfig dbConfig = new DatabaseConfig();
		dbConfig.setAllowCreate(true);
		dbConfig.setTransactional(true);
		chkDB = environment.openDatabase(null,"CHK",dbConfig);
		
		fixSecondaryFile = new File(storeDir, "recreate_secondary_db");
		
		if(fixSecondaryFile.exists()) {
			fixSecondaryFile.delete();
			Logger.error(this, "Recreating secondary database for "+storeDir);
			Logger.error(this, "This may take some time...");
			System.err.println("Recreating secondary database for "+storeDir);
			System.err.println("This may take some time...");
			environment.truncateDatabase(null, "CHK_accessTime", false);
		}
		
		// Initialize secondary CHK database sorted on accesstime
		SecondaryConfig secDbConfig = new SecondaryConfig();
		secDbConfig.setAllowCreate(true);
		secDbConfig.setSortedDuplicates(true);
		secDbConfig.setTransactional(true);
		secDbConfig.setAllowPopulate(true);
		storeBlockTupleBinding = new StoreBlockTupleBinding();
		longTupleBinding = TupleBinding.getPrimitiveBinding(Long.class);
		AccessTimeKeyCreator accessTimeKeyCreator = 
			new AccessTimeKeyCreator(storeBlockTupleBinding);
		secDbConfig.setKeyCreator(accessTimeKeyCreator);
		chkDB_accessTime = environment.openSecondaryDatabase
							(null, "CHK_accessTime", chkDB, secDbConfig);
		
		// Initialize the store file
		File storeFile = new File(dir,"store");
		if(!storeFile.exists())
			storeFile.createNewFile();
		chkStore = new RandomAccessFile(storeFile,"rw");
			
		chkBlocksInStore = countCHKBlocks();
		lastRecentlyUsed = getMaxRecentlyUsed();
		
//		 Add shutdownhook
		Runtime.getRuntime().addShutdownHook(new ShutdownHook());
	}
	
	/**
     * Retrieve a block.
     * @param dontPromote If true, don't promote data if fetched.
     * @return null if there is no such block stored, otherwise the block.
     */
    public CHKBlock fetch(NodeCHK chk, boolean dontPromote) throws IOException
    {
    	if(closed)
    		return null;
    	
    	byte[] routingkey = chk.getRoutingKey();
    	DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
    	DatabaseEntry blockDBE = new DatabaseEntry();
    	Cursor c = null;
    	Transaction t = null;
    	try{
    		t = environment.beginTransaction(null,null);
    		c = chkDB.openCursor(t,null);

    		/**
    		 * We will have to write, unless both dontPromote and the key is valid.
    		 * The lock only applies to this record, so it's not a big problem for our use.
    		 * What *IS* a big problem is that if we take a LockMode.DEFAULT, and two threads
    		 * access the same key, they will both take the read lock, and then both try to
    		 * take the write lock. Neither can relinquish the read in order for the other to
    		 * take the write, so we're screwed.
    		 */
    		if(c.getSearchKey(routingkeyDBE,blockDBE,LockMode.RMW)
    				!=OperationStatus.SUCCESS) {
    			c.close();
    			t.abort();
    			return null;
    		}

	    	StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
	    		    	
	    	CHKBlock block = null;
	    	try{
	    		byte[] header = new byte[headerBlockSize];
	    		byte[] data = new byte[dataBlockSize];
	    		synchronized(chkStore) {
	    			long seekTarget = storeBlock.offset*(long)(dataBlockSize+headerBlockSize);
	    	  	try {
		    			chkStore.seek(seekTarget);
		    		} catch (IOException ioe) {
	    				if(seekTarget > (2*1024*1024*1024))
	    					Logger.error(this, "Environment does not support files greater than 2 GB?");
	    					System.out.println("Environment does not support files greater than 2 GB? (exception to follow)");
		    			Logger.error(this, "Caught IOException on chkStore.seek("+seekTarget+")");
		    			throw ioe;
		    		}
		    		chkStore.readFully(header);
		    		chkStore.readFully(data);
	    		}
	    		
	    		
	    		block = new CHKBlock(data,header,chk);
	    		
	    		if(!dontPromote)
	    		{
	    			storeBlock.updateRecentlyUsed();
	    			DatabaseEntry updateDBE = new DatabaseEntry();
	    			storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
	    			c.putCurrent(updateDBE);
		    		c.close();
		    		t.commit();
	    		}else{
	    			c.close();
	    			t.abort();
	    		}
	    		
	    		Logger.minor(this, "Get key: "+chk);
	            Logger.minor(this, "Headers: "+header.length+" bytes, hash "+header);
	            Logger.minor(this, "Data: "+data.length+" bytes, hash "+data);
	    		
	    	}catch(CHKVerifyException ex){
	    		Logger.normal(this, "CHKBlock: Does not verify ("+ex+"), setting accessTime to 0 for : "+chk);
	    		storeBlock.setRecentlyUsedToZero();
    			DatabaseEntry updateDBE = new DatabaseEntry();
    			storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
    			c.putCurrent(updateDBE);
	    		c.close();
	    		t.commit();
	            return null;
	    	}
	    	return block;
    	}catch(Throwable ex) {  // FIXME: ugly  
    		if(c!=null) {
    			try{c.close();}catch(DatabaseException ex2){}
    		}
    		if(t!=null)
    			try{t.abort();}catch(DatabaseException ex2){}
           	checkSecondaryDatabaseError(ex);
    		Logger.error(this, "Caught "+ex, ex);
    		ex.printStackTrace();
        	throw new IOException(ex.getMessage());
        }
    	
//    	return null;
    }

	/**
     * Retrieve a block.
     * @param dontPromote If true, don't promote data if fetched.
     * @return null if there is no such block stored, otherwise the block.
     */
    public SSKBlock fetch(NodeSSK chk, boolean dontPromote) throws IOException
    {
    	if(closed)
    		return null;
    	
    	byte[] routingkey = chk.getRoutingKey();
    	DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
    	DatabaseEntry blockDBE = new DatabaseEntry();
    	Cursor c = null;
    	Transaction t = null;
    	try{
    		t = environment.beginTransaction(null,null);
    		c = chkDB.openCursor(t,null);
    		
    		if(c.getSearchKey(routingkeyDBE,blockDBE,LockMode.RMW)
    				!=OperationStatus.SUCCESS) {
    			c.close();
    			t.abort();
    			return null;
    		}

	    	StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
	    		    	
	    	SSKBlock block = null;
	    	try{
	    		byte[] header = new byte[headerBlockSize];
	    		byte[] data = new byte[dataBlockSize];
	    		synchronized(chkStore) {
		    		chkStore.seek(storeBlock.offset*(long)(dataBlockSize+headerBlockSize));
		    		chkStore.readFully(header);
		    		chkStore.readFully(data);
	    		}
	    		
	    		
	    		block = new SSKBlock(data,header,chk, true);
	    		
	    		if(!dontPromote)
	    		{
	    			storeBlock.updateRecentlyUsed();
	    			DatabaseEntry updateDBE = new DatabaseEntry();
	    			storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
	    			c.putCurrent(updateDBE);
		    		c.close();
		    		t.commit();
	    		}else{
	    			c.close();
	    			t.abort();
	    		}
	    		
	    		Logger.minor(this, "Get key: "+chk);
	            Logger.minor(this, "Headers: "+header.length+" bytes, hash "+header);
	            Logger.minor(this, "Data: "+data.length+" bytes, hash "+data);
	    		
	    	}catch(SSKVerifyException ex){
	    		Logger.normal(this, "SSHBlock: Does not verify ("+ex+"), setting accessTime to 0 for : "+chk, ex);
	    		storeBlock.setRecentlyUsedToZero();
    			DatabaseEntry updateDBE = new DatabaseEntry();
    			storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
    			c.putCurrent(updateDBE);
	    		c.close();
	    		t.commit();
	            return null;
	    	}
	    	return block;
    	}catch(Throwable ex) {  // FIXME: ugly  
    		if(c!=null) {
    			try{c.close();}catch(DatabaseException ex2){}
    		}
    		if(t!=null) {
    			try{t.abort();}catch(DatabaseException ex2){}
    		}
        	checkSecondaryDatabaseError(ex);
    		Logger.error(this, "Caught "+ex, ex);
    		ex.printStackTrace();
        	throw new IOException(ex.getMessage());
        }
    	
//    	return null;
    }

    // FIXME do this with interfaces etc.
    
	/**
     * Retrieve a block.
     * @param dontPromote If true, don't promote data if fetched.
     * @return null if there is no such block stored, otherwise the block.
     */
    public DSAPublicKey fetchPubKey(byte[] hash, boolean dontPromote) throws IOException
    {
    	if(closed)
    		return null;
    	
    	DatabaseEntry routingkeyDBE = new DatabaseEntry(hash);
    	DatabaseEntry blockDBE = new DatabaseEntry();
    	Cursor c = null;
    	Transaction t = null;
    	try{
    		t = environment.beginTransaction(null,null);
    		c = chkDB.openCursor(t,null);
    		
    		if(c.getSearchKey(routingkeyDBE,blockDBE,LockMode.RMW)
    				!=OperationStatus.SUCCESS) {
    			c.close();
    			t.abort();
    			return null;
    		}

	    	StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
	    		    	
	    	DSAPublicKey block = null;
	    	
	    		byte[] data = new byte[dataBlockSize];
	    		Logger.minor(this, "Reading from store... "+storeBlock.offset+" ("+storeBlock.recentlyUsed+")");
	    		synchronized(chkStore) {
		    		chkStore.seek(storeBlock.offset*(long)(dataBlockSize+headerBlockSize));
		    		chkStore.readFully(data);
	    		}
	    		Logger.minor(this, "Read");
	    		
	    		try {
	    			block = new DSAPublicKey(data);
	    		} catch (IOException e) {
	    			Logger.error(this, "Could not read key");
	    			c.close();
	    			t.abort();
	    			return null;
	    		}
	    		
	    		if(!Arrays.equals(block.asBytesHash(), hash)) {
		    		Logger.normal(this, "DSAPublicKey: Does not verify (unequal hashes), setting accessTime to 0 for : "+HexUtil.bytesToHex(hash));
		    		storeBlock.setRecentlyUsedToZero();
	    			DatabaseEntry updateDBE = new DatabaseEntry();
	    			storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
	    			c.putCurrent(updateDBE);
		    		c.close();
		    		t.commit();
		            return null;
	    		}
	    		
	    		if(!dontPromote)
	    		{
	    			storeBlock.updateRecentlyUsed();
	    			DatabaseEntry updateDBE = new DatabaseEntry();
	    			storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
	    			c.putCurrent(updateDBE);
		    		c.close();
		    		t.commit();
	    		}else{
	    			c.close();
	    			t.abort();
	    		}
	    		
	    		Logger.minor(this, "Get key: "+HexUtil.bytesToHex(hash));
	            Logger.minor(this, "Data: "+data.length+" bytes, hash "+data);
	    		
	    	return block;
    	}catch(Throwable ex) {  // FIXME: ugly  
    		if(c!=null) {
    			try{c.close();}catch(DatabaseException ex2){}
    		}
    		if(t!=null) {
    			try{t.abort();}catch(DatabaseException ex2){}
    		}
        	checkSecondaryDatabaseError(ex);
    		Logger.error(this, "Caught "+ex, ex);
    		ex.printStackTrace();
        	throw new IOException(ex.getMessage());
        }
    	
//    	return null;
    }

    public void put(CHKBlock b) throws IOException {
		NodeCHK chk = (NodeCHK) b.getKey();
		CHKBlock oldBlock = fetch(chk, false);
		if(oldBlock != null)
			return;
		innerPut(b);
    }
    
    public void put(SSKBlock b, boolean overwrite) throws IOException, KeyCollisionException {
		NodeSSK ssk = (NodeSSK) b.getKey();
		SSKBlock oldBlock = fetch(ssk, false);
		if(oldBlock != null) {
			if(!b.equals(oldBlock)) {
				if(!overwrite)
					throw new KeyCollisionException();
				else {
					if(overwrite(b)) {
						fetch(ssk, false); // promote it
						return;
					}
				}
			}
			return;
		}
		innerPut(b);
    }
    
    private boolean overwrite(SSKBlock b) throws IOException {
    	NodeSSK chk = (NodeSSK) b.getKey();
    	byte[] routingkey = chk.getRoutingKey();
    	DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
    	DatabaseEntry blockDBE = new DatabaseEntry();
    	Cursor c = null;
    	Transaction t = null;
    	try{
    		t = environment.beginTransaction(null,null);
    		c = chkDB.openCursor(t,null);
    		
    		if(c.getSearchKey(routingkeyDBE,blockDBE,LockMode.RMW)
    				!=OperationStatus.SUCCESS) {
    			c.close();
    			t.abort();
    			return false;
    		}

	    	StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
	    		    	
	    	byte[] header = b.getRawHeaders();
	    	byte[] data = b.getRawData();
	    	synchronized(chkStore) {
		    	chkStore.seek(storeBlock.offset*(long)(dataBlockSize+headerBlockSize));
			
		    	chkStore.write(header);
		    	chkStore.write(data);
	    	}
	    		
	    } catch(Throwable ex) {  // FIXME: ugly  
	    	if(c!=null) {
	    		try{c.close();}catch(DatabaseException ex2){}
	    	
	    	}
	    	if(t!=null) {
	    		try{t.abort();}catch(DatabaseException ex2){}
	    	}
	
	    	checkSecondaryDatabaseError(ex);
    		Logger.error(this, "Caught "+ex, ex);
    		ex.printStackTrace();
        	throw new IOException(ex.getMessage());
        }
	    	
    	return true;
	}

	/**
     * Store a block.
     */
    void innerPut(KeyBlock block) throws IOException
    {   	
    	if(closed)
    		return;
    	  	
    	byte[] routingkey = block.getKey().getRoutingKey();
        byte[] data = block.getRawData();
        byte[] header = block.getRawHeaders();
        
        if(data.length!=dataBlockSize) {
        	Logger.error(this, "This data is "+data.length+" bytes. Should be "+dataBlockSize);
        	return;
        }
        if(header.length!=headerBlockSize) {
        	Logger.error(this, "This header is "+data.length+" bytes. Should be "+headerBlockSize);
        	return;
        }
        
        Transaction t = null;
        
        try{
        	t = environment.beginTransaction(null,null);
        	DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
        	
        	synchronized(chkStore) {
	        	if(chkBlocksInStore<maxChkBlocks) {
	        		// Expand the store file
	        		long byteOffset = chkBlocksInStore*(dataBlockSize+headerBlockSize);
	        		StoreBlock storeBlock = new StoreBlock(chkBlocksInStore);
	        		DatabaseEntry blockDBE = new DatabaseEntry();
	    	    	storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
	    	        chkDB.put(t,routingkeyDBE,blockDBE);
	    	        try {
	    	          chkStore.seek(byteOffset);
	    	        } catch (IOException ioe) {
	    	          if(byteOffset > (2*1024*1024*1024))
	    	            Logger.error(this, "Environment does not support files greater than 2 GB?");
	    	            System.out.println("Environment does not support files greater than 2 GB? (exception to follow)");
	    	          Logger.error(this, "Caught IOException on chkStore.seek("+byteOffset+")");
		              throw ioe;
	    	        }
	    	        chkStore.write(header);
	    	        chkStore.write(data);
	    	        t.commit();
	    	        chkBlocksInStore++;
	        	}else{
	        		// Overwrite an other block
	        		Cursor c = chkDB_accessTime.openCursor(t,null);
	        		DatabaseEntry keyDBE = new DatabaseEntry();
	        		DatabaseEntry dataDBE = new DatabaseEntry();
	        		c.getFirst(keyDBE,dataDBE,null);
	        		StoreBlock oldStoreBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(dataDBE);
	        		c.delete();
	        		c.close();
	        		StoreBlock storeBlock = new StoreBlock(oldStoreBlock.getOffset());
	        		DatabaseEntry blockDBE = new DatabaseEntry();
	        		storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
	        		chkDB.put(t,routingkeyDBE,blockDBE);
	    	        chkStore.seek(storeBlock.getOffset()*(long)(dataBlockSize+headerBlockSize));
	    	        chkStore.write(header);
	    	        chkStore.write(data);
	        		t.commit();
	        	}
        	}
        	
	    	Logger.minor(this, "Put key: "+block.getKey());
	        Logger.minor(this, "Headers: "+header.length+" bytes, hash "+Fields.hashCode(header));
	        Logger.minor(this, "Data: "+data.length+" bytes, hash "+Fields.hashCode(data));
                
        }catch(Throwable ex) {  // FIXME: ugly  
        	if(t!=null){
        		try{t.abort();}catch(DatabaseException ex2){};
        	}
        	checkSecondaryDatabaseError(ex);
        	Logger.error(this, "Caught "+ex, ex);
        	ex.printStackTrace();
        	throw new IOException(ex.getMessage());
        }
    }
    
    private void checkSecondaryDatabaseError(Throwable ex) {
    	if(ex instanceof DatabaseException && ex.getMessage().indexOf("missing key in the primary database") > -1) {
    		try {
				fixSecondaryFile.createNewFile();
			} catch (IOException e) {
				Logger.error(this, "Corrupt secondary database but could not create flag file "+fixSecondaryFile);
				System.err.println("Corrupt secondary database but could not create flag file "+fixSecondaryFile);
				return; // Not sure what else we can do
			}
    		Logger.error(this, "Corrupt secondary database. Should be cleaned up on restart.");
    		System.err.println("Corrupt secondary database. Should be cleaned up on restart.");
    		System.exit(freenet.node.Node.EXIT_DATABASE_REQUIRES_RESTART);
    	}
	}

	/**
     * Store a block.
     */
    public void put(byte[] hash, DSAPublicKey key) throws IOException
    {   	
    	if(closed)
    		return;
    	  	
    	byte[] routingkey = hash;
        byte[] data = key.asPaddedBytes();
        
        if(data.length!=dataBlockSize) {
        	Logger.error(this, "This data is "+data.length+" bytes. Should be "+dataBlockSize);
        	return;
        }
        
        Transaction t = null;
        
        try{
        	t = environment.beginTransaction(null,null);
        	DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
        	
        	synchronized(chkStore) {
	        	if(chkBlocksInStore<maxChkBlocks) {
	        		// Expand the store file
	        		long byteOffset = chkBlocksInStore*(dataBlockSize+headerBlockSize);
	        		StoreBlock storeBlock = new StoreBlock(chkBlocksInStore);
	        		DatabaseEntry blockDBE = new DatabaseEntry();
	    	    	storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
	    	        chkDB.put(t,routingkeyDBE,blockDBE);
	    	        chkStore.seek(byteOffset);
	    	        chkStore.write(data);
	    	        t.commit();
	    	        chkBlocksInStore++;
	        	}else{
	        		// Overwrite an other block
	        		Cursor c = chkDB_accessTime.openCursor(t,null);
	        		DatabaseEntry keyDBE = new DatabaseEntry();
	        		DatabaseEntry dataDBE = new DatabaseEntry();
	        		c.getFirst(keyDBE,dataDBE,null);
	        		StoreBlock oldStoreBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(dataDBE);
	        		c.delete();
	        		c.close();
	        		StoreBlock storeBlock = new StoreBlock(oldStoreBlock.getOffset());
	        		DatabaseEntry blockDBE = new DatabaseEntry();
	        		storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
	        		chkDB.put(t,routingkeyDBE,blockDBE);
	    	        chkStore.seek(storeBlock.getOffset()*(long)(dataBlockSize+headerBlockSize));
	    	        chkStore.write(data);
	        		t.commit();
	        	}
        	}
        	
	    	Logger.minor(this, "Put key: "+HexUtil.bytesToHex(hash));
	        Logger.minor(this, "Data: "+data.length+" bytes, hash "+Fields.hashCode(data));
                
        }catch(Throwable ex) {  // FIXME: ugly  
        	if(t!=null){
        		try{t.abort();}catch(DatabaseException ex2){};
        	}
        	checkSecondaryDatabaseError(ex);
        	Logger.error(this, "Caught "+ex, ex);
        	ex.printStackTrace();
        	throw new IOException(ex.getMessage());
        }
    }
    
    private class StoreBlock
    {
    	private long recentlyUsed;
    	private long offset;
    	
    	public StoreBlock(long offset)
    	{
    		this(offset,getNewRecentlyUsed());
    	}
    	
    	public StoreBlock(long offset,long recentlyUsed)
    	{
    		this.offset = offset;
    		this.recentlyUsed = recentlyUsed;
    	}
    	    	
   	
    	public long getRecentlyUsed() {
    		return recentlyUsed;
    	}
    	
    	public void setRecentlyUsedToZero()
    	{
    		recentlyUsed = 0;
    	}
    	
    	public void updateRecentlyUsed()
    	{
    		recentlyUsed = getNewRecentlyUsed();
    	}
    	
    	public long getOffset() {
    		return offset;
    	}
    }
    
    /**
     * Convert StoreBlock's to the format used by the database
     */
    private class StoreBlockTupleBinding extends TupleBinding
    {

    	public void objectToEntry(Object object, TupleOutput to)  {
    		StoreBlock myData = (StoreBlock)object;

    		to.writeLong(myData.getOffset());
    		to.writeLong(myData.getRecentlyUsed());
    	}

    	public Object entryToObject(TupleInput ti) {
    		if(Logger.shouldLog(Logger.DEBUG, this))
    			Logger.debug(this, "Available: "+ti.available());
    		long offset = ti.available() == 12 ? ti.readInt() : ti.readLong();
	    	long lastAccessed = ti.readLong();
	    	
	    	StoreBlock storeBlock = new StoreBlock(offset,lastAccessed);
	    	return storeBlock;
    	}
    }
      
    /**
     * Used to create the secondary database sorted on accesstime
     */
    private class AccessTimeKeyCreator implements SecondaryKeyCreator {
    	private TupleBinding theBinding;
    	
    	public AccessTimeKeyCreator(TupleBinding theBinding1) {
    		theBinding = theBinding1;
    	}
    	
    	public boolean createSecondaryKey(SecondaryDatabase secDb,
    	DatabaseEntry keyEntry,
    	DatabaseEntry dataEntry,
    	DatabaseEntry resultEntry) {

    		StoreBlock storeblock = (StoreBlock) theBinding.entryToObject(dataEntry);
    		Long accessTime = new Long(storeblock.getRecentlyUsed());
    		longTupleBinding.objectToEntry(accessTime, resultEntry);
    		return true;
    	}
    }

    private class ShutdownHook extends Thread
    {
    	public void run() {
    		close();
    	}
    }
    
    private void close() {
    	try{
			// FIXME: 	we should be sure all access to the database has stopped
			//			before we try to close it. Currently we just guess
    		//			This is nothing too problematic however since the worst thing that should
    		//			happen is that we miss the last few store()'s and get an exception.
			Logger.minor(this, "Closing database.");
			closed=true;
			// Give all threads some time to complete
			Thread.sleep(5000);
			chkStore.close();
    		chkDB_accessTime.close();
    		chkDB.close();
    		environment.close();
    		Logger.minor(this, "Closing database finished.");
		}catch(Exception ex){
			Logger.error(this,"Error while closing database.",ex);
			ex.printStackTrace();
		}
    }
    
    private long countCHKBlocks() {
    	long count =0;
    	try{
    		Logger.minor(this, "Started counting items in database");
    		
    		count = ((BtreeStats)chkDB.getStats(null)).getLeafNodeCount();
    		
    		Logger.minor(this, "Counted "+count+" items in database");
    	}catch(DatabaseException ex){
    		Logger.minor(this, "Exception while counting items in database",ex);
    	}
    	return count;
    }
    
    private long getMaxRecentlyUsed()
    {
    	long maxRecentlyUsed = 0;
    	
    	try{
	    	Cursor c = chkDB_accessTime.openCursor(null,null);
			DatabaseEntry keyDBE = new DatabaseEntry();
			DatabaseEntry dataDBE = new DatabaseEntry();
			if(c.getLast(keyDBE,dataDBE,null)==OperationStatus.SUCCESS) {
				StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(dataDBE);
				maxRecentlyUsed = storeBlock.getRecentlyUsed();
			}
			c.close();
    	}catch(DatabaseException ex){ex.printStackTrace();}
    	
    	return maxRecentlyUsed;
    }
    
    private synchronized long getNewRecentlyUsed() {
    	lastRecentlyUsed++;
    	return lastRecentlyUsed;
    }

	public void setMaxKeys(long maxStoreKeys) {
		maxChkBlocks = maxStoreKeys;
	}
}