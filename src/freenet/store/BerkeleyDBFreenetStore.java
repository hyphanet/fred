package freenet.store;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashSet;

import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DatabaseNotFoundException;
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
 * 
 * 
 * FIXME:
 * This should in theory be threadsafe without the Big Lock.
 * Remove the big lock, when we are sure that the major issues are sorted.
 */
public class BerkeleyDBFreenetStore implements FreenetStore {

    final int dataBlockSize;
    final int headerBlockSize;
	
	private final Environment environment;
	private final TupleBinding storeBlockTupleBinding;
	private final TupleBinding longTupleBinding;
	private final File fixSecondaryFile;
	
	private long chkBlocksInStore;
	private final Object chkBlocksInStoreLock = new Object();
	private long maxChkBlocks;
	private final Database chkDB;
	private final Database chkDB_accessTime;
	private final Database chkDB_blockNum;
	private final RandomAccessFile chkStore;
	
	private long lastRecentlyUsed;
	private final Object lastRecentlyUsedSync = new Object();
	
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
		
		// Initialize other secondary database sorted on block number
//		try {
//			environment.removeDatabase(null, "CHK_blockNum");
//		} catch (DatabaseNotFoundException e) { };
		SecondaryConfig blockNoDbConfig = new SecondaryConfig();
		blockNoDbConfig.setAllowCreate(false);
		blockNoDbConfig.setSortedDuplicates(false);
		blockNoDbConfig.setAllowPopulate(true);
		blockNoDbConfig.setTransactional(true);
		
		BlockNumberKeyCreator bnkc = 
			new BlockNumberKeyCreator(storeBlockTupleBinding);
		blockNoDbConfig.setKeyCreator(bnkc);
		SecondaryDatabase blockNums;
		try {
			System.err.println("Opening block db index");
			blockNums = environment.openSecondaryDatabase
				(null, "CHK_blockNum", chkDB, blockNoDbConfig);
		} catch (DatabaseNotFoundException e) {
			System.err.println("Migrating block db index");
			// De-dupe on keys and block numbers.
			migrate(storeDir);
			System.err.println("De-duped, creating new index...");
			blockNoDbConfig.setSortedDuplicates(false);
			blockNoDbConfig.setAllowCreate(true);
			blockNoDbConfig.setAllowPopulate(true);
			blockNums = environment.openSecondaryDatabase
				(null, "CHK_blockNum", chkDB, blockNoDbConfig);
		}
		
		chkDB_blockNum = blockNums;
		
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

	public static final short TYPE_CHK = 0;
	public static final short TYPE_PUBKEY = 1;
	public static final short TYPE_SSK = 2;
	
	/**
     * Recreate the index from the data file. Call this when the index has been corrupted.
     * @param the directory where the store is located
     * @throws FileNotFoundException if the dir does not exist and could not be created
     */
	public BerkeleyDBFreenetStore(String storeDir, long maxChkBlocks, int blockSize, int headerSize, short type) throws Exception {
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
		
		// Delete old database.
		
		File dir = new File(storeDir);
		if(!dir.exists())
			dir.mkdir();
		File dbDir = new File(dir,"database");
		if(dbDir.exists()) {
			File[] files = dbDir.listFiles();
			for(int i=0;i<files.length;i++)
				files[i].delete();
		} else
			dbDir.mkdir();
		
		// Now create a new one.
		
		// Initialize environment
		EnvironmentConfig envConfig = new EnvironmentConfig();
		envConfig.setAllowCreate(true);
		envConfig.setTransactional(true);
		envConfig.setTxnWriteNoSync(true);

		environment = new Environment(dbDir, envConfig);
		
		// Initialize CHK database
		DatabaseConfig dbConfig = new DatabaseConfig();
		dbConfig.setAllowCreate(true);
		dbConfig.setTransactional(true);
		chkDB = environment.openDatabase(null,"CHK",dbConfig);
		
		fixSecondaryFile = new File(storeDir, "recreate_secondary_db");
		fixSecondaryFile.delete();
		
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
		
		// Initialize other secondary database sorted on block number
//		try {
//			environment.removeDatabase(null, "CHK_blockNum");
//		} catch (DatabaseNotFoundException e) { };
		SecondaryConfig blockNoDbConfig = new SecondaryConfig();
		blockNoDbConfig.setAllowCreate(true);
		blockNoDbConfig.setSortedDuplicates(false);
		blockNoDbConfig.setAllowPopulate(true);
		blockNoDbConfig.setTransactional(true);
		
		BlockNumberKeyCreator bnkc = 
			new BlockNumberKeyCreator(storeBlockTupleBinding);
		blockNoDbConfig.setKeyCreator(bnkc);
		SecondaryDatabase blockNums;
		System.err.println("Creating block db index");
		chkDB_blockNum = environment.openSecondaryDatabase
			(null, "CHK_blockNum", chkDB, blockNoDbConfig);
		
		// Initialize the store file
		File storeFile = new File(dir,"store");
		if(!storeFile.exists())
			storeFile.createNewFile();
		chkStore = new RandomAccessFile(storeFile,"rw");
		
		chkBlocksInStore = 0;
		
		lastRecentlyUsed = 0;
		
		reconstruct(type, storeDir);
			
		chkBlocksInStore = countCHKBlocks();
		lastRecentlyUsed = getMaxRecentlyUsed();
		
//		 Add shutdownhook
		Runtime.getRuntime().addShutdownHook(new ShutdownHook());
	}
	
	private void reconstruct(short type, String storeDir) throws DatabaseException {
		if(type == TYPE_SSK) {
			System.err.println("Reconstruction of SSK store not supported at present.");
			throw new UnsupportedOperationException("Reconstruction of SSK store not supported at present.");
			// FIXME we would need to pass in a means to fetch the pubkeys (an already-working BDBFS maybe).
			// This could be via an interface. It might be implemented by the node so we can use the in-RAM cache.
		}
		System.err.println("Reconstructing store index from store file: type="+type);
		Logger.error(this, "Reconstructing store index from store file: type="+type);
		byte[] header = new byte[headerBlockSize];
		byte[] data = new byte[dataBlockSize];
		try {
			chkStore.seek(0);
			long l = 0;
			while(true) {
				Transaction t = null;
				try {
					chkStore.readFully(header);
					chkStore.readFully(data);
					byte[] routingkey = null;
					if(type == TYPE_CHK) {
						try {
							CHKBlock chk = new CHKBlock(header, data, null);
							routingkey = chk.getKey().getRoutingKey();
						} catch (CHKVerifyException e) {
							Logger.error(this, "Bogus key at slot "+l+" : "+e, e);
						}
					} else if(type == TYPE_PUBKEY) {
						DSAPublicKey key = new DSAPublicKey(data);
						routingkey = key.asBytesHash();
					} else {
						l++;
						continue;
					}
					t = environment.beginTransaction(null,null);
					long blockNum = chkBlocksInStore++;
					StoreBlock storeBlock = new StoreBlock(blockNum);
					DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
					DatabaseEntry blockDBE = new DatabaseEntry();
					storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
					chkDB.put(t,routingkeyDBE,blockDBE);
					t.commit();
					t = null;
				} finally {
					l++;
					if(t != null) t.abort();
				}
			}
		} catch (EOFException e) {
			migrate(storeDir);
			return;
		} catch (IOException e) {
			Logger.error(this, "Caught "+e, e);
			throw new Error(e);
			// What else can we do? FIXME
		}
	}

	/**
	 * Migrate from a store which didn't have a unique index on blockNum, to one which does.
	 * How do we do this? We scan through all entries (slow), we fetch each key, delete all data's
	 * under it, and then insert the one we are looking at.
	 * 
	 * FIXME: Create a list of reusable block numbers?
	 */
	private void migrate(String storeDir) throws DatabaseException {
		
		System.err.println("Migrating database "+storeDir+": Creating unique index on block number");
		HashSet s = new HashSet();
		
    	Cursor c = null;
    	Transaction t = null;
		t = environment.beginTransaction(null,null);
		c = chkDB.openCursor(t,null);
		DatabaseEntry keyDBE = new DatabaseEntry();
		DatabaseEntry blockDBE = new DatabaseEntry();
		OperationStatus opStat;
		opStat = c.getLast(keyDBE, blockDBE, LockMode.RMW);
		if(opStat == OperationStatus.NOTFOUND) {
			System.err.println("Database is empty.");
			return;
		}
		Logger.minor(this, "Found first key");
		try {
			int x = 0;
			while(true) {
		    	StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
				Logger.minor(this, "Found another key ("+(x++)+") ("+storeBlock.offset+")");
				Long l = new Long(storeBlock.offset);
				if(s.contains(l)) {
					Logger.minor(this, "Deleting (block number conflict).");
					chkDB.delete(t, keyDBE);
				}
				s.add(l);
				opStat = c.getPrev(keyDBE, blockDBE, LockMode.RMW);
				if(opStat == OperationStatus.NOTFOUND) {
					return;
				}
			}
		} catch (DatabaseException e) {
			System.err.println("Caught: "+e);
			e.printStackTrace();
			Logger.error(this, "Caught "+e, e);
			t.abort();
			t = null;
		} finally {
			c.close();
			if(t != null)
				t.commit();
		}
	}

	/**
     * Retrieve a block.
     * @param dontPromote If true, don't promote data if fetched.
     * @return null if there is no such block stored, otherwise the block.
     */
    public synchronized CHKBlock fetch(NodeCHK chk, boolean dontPromote) throws IOException {
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
    			t = null;
    			c = null;
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
	    				if(seekTarget > (2*1024*1024*1024)) {
	    					Logger.error(this, "Environment does not support files bigger than 2 GB?");
	    					System.out.println("Environment does not support files bigger than 2 GB? (exception to follow)");
	    				}
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
		    		c = null;
		    		t = null;
	    		}else{
	    			c.close();
	    			t.abort();
	    			c = null;
	    			t = null;
	    		}
	    		
	    		Logger.minor(this, "Get key: "+chk);
	            Logger.minor(this, "Headers: "+header.length+" bytes, hash "+header);
	            Logger.minor(this, "Data: "+data.length+" bytes, hash "+data);
	    		
	    	}catch(CHKVerifyException ex){
	    		Logger.error(this, "CHKBlock: Does not verify ("+ex+"), setting accessTime to 0 for : "+chk);
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
    public synchronized SSKBlock fetch(NodeSSK chk, boolean dontPromote) throws IOException {
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
    			c = null;
    			t = null;
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
	    		
	    		if(!dontPromote) {
	    			storeBlock.updateRecentlyUsed();
	    			DatabaseEntry updateDBE = new DatabaseEntry();
	    			storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
	    			c.putCurrent(updateDBE);
		    		c.close();
		    		t.commit();
		    		c = null;
		    		t = null;
	    		}else{
	    			c.close();
	    			t.abort();
	    			c = null;
	    			t = null;
	    		}
	    		
	    		Logger.minor(this, "Get key: "+chk);
	            Logger.minor(this, "Headers: "+header.length+" bytes, hash "+header);
	            Logger.minor(this, "Data: "+data.length+" bytes, hash "+data);
	    		
	    	}catch(SSKVerifyException ex){
	    		Logger.normal(this, "SSKBlock: Does not verify ("+ex+"), setting accessTime to 0 for : "+chk, ex);
	    		storeBlock.setRecentlyUsedToZero();
    			DatabaseEntry updateDBE = new DatabaseEntry();
    			storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
    			c.putCurrent(updateDBE);
	    		c.close();
	    		t.commit();
	    		c = null;
	    		t = null;
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
    
    public synchronized DSAPublicKey fetchPubKey(byte[] hash, boolean dontPromote) throws IOException {
    	return fetchPubKey(hash, null, dontPromote);
    }
    
	/**
     * Retrieve a block.
     * @param dontPromote If true, don't promote data if fetched.
     * @param replacement If non-null, and the data exists but is corrupt, replace it with this.
     * @return null if there is no such block stored, otherwise the block.
     */
    public synchronized DSAPublicKey fetchPubKey(byte[] hash, DSAPublicKey replacement, boolean dontPromote) throws IOException {
    	if(closed)
    		return null;
    	
    	DatabaseEntry routingkeyDBE = new DatabaseEntry(hash);
    	DatabaseEntry blockDBE = new DatabaseEntry();
    	Cursor c = null;
    	Transaction t = null;
    	try{
    		t = environment.beginTransaction(null,null);
    		c = chkDB.openCursor(t,null);

    		// Lock the records as soon as we find them.
    		if(c.getSearchKey(routingkeyDBE,blockDBE,LockMode.RMW)
    				!=OperationStatus.SUCCESS) {
    			c.close();
    			t.abort();
    			c = null;
    			t = null;
    			return null;
    		}

	    	StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
	    	
	    	// Promote the key (we can always demote it later; promoting it here means it shouldn't be deallocated
	    	// FIXME the locking/concurrency in this class is a bit dodgy!
	    	
    		if(!dontPromote) {
    			storeBlock.updateRecentlyUsed();
    			DatabaseEntry updateDBE = new DatabaseEntry();
    			storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
    			c.putCurrent(updateDBE);
    		}
    		
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
	    			c = null;
	    			t = null;
	    			return null;
	    		}

	    		if(!Arrays.equals(block.asBytesHash(), hash)) {
	    			
	    			if(replacement != null) {
	    				Logger.normal(this, "Replacing corrupt DSAPublicKey ("+HexUtil.bytesToHex(hash));
	    				synchronized(chkStore) {
	    					chkStore.seek(storeBlock.offset*(long)(dataBlockSize+headerBlockSize));
	    					byte[] toWrite = block.asPaddedBytes();
	    					chkStore.write(toWrite);
	    				}
	    			} else {
	    				Logger.error(this, "DSAPublicKey: Does not verify (unequal hashes), setting accessTime to 0 for : "+HexUtil.bytesToHex(hash));
	    				storeBlock.setRecentlyUsedToZero();
	    				DatabaseEntry updateDBE = new DatabaseEntry();
	    				storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
	    				c.putCurrent(updateDBE);
	    				c.close();
	    				t.commit();
	    				t = null;
	    				c = null;
	    				return null;
	    			}
	    		}

	    		// Finished, commit.
	    		c.close();
	    		t.commit();
	    		c = null;
	    		t = null;
	    		
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

    public synchronized void put(CHKBlock b) throws IOException {
		NodeCHK chk = (NodeCHK) b.getKey();
		CHKBlock oldBlock = fetch(chk, false);
		if(oldBlock != null)
			return;
		innerPut(b);
    }
    
    public synchronized void put(SSKBlock b, boolean overwrite) throws IOException, KeyCollisionException {
		NodeSSK ssk = (NodeSSK) b.getKey();
		SSKBlock oldBlock = fetch(ssk, false);
		if(oldBlock != null) {
			if(!b.equals(oldBlock)) {
				if(!overwrite)
					throw new KeyCollisionException();
				else {
					overwrite(b);
				}
			}
		} else {
			innerPut(b);
		}
    }
    
    private synchronized boolean overwrite(SSKBlock b) throws IOException {
    	NodeSSK chk = (NodeSSK) b.getKey();
    	byte[] routingkey = chk.getRoutingKey();
    	DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
    	DatabaseEntry blockDBE = new DatabaseEntry();
    	Cursor c = null;
    	Transaction t = null;
    	try{
    		t = environment.beginTransaction(null,null);
    		c = chkDB.openCursor(t,null);

    		// Lock the record.
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
	    	
	    	// Unlock record.
	    	c.close();
	    	c = null;
	    	t.commit();
	    	t = null;
	    	
	    } catch(Throwable ex) {  // FIXME: ugly  
	    	checkSecondaryDatabaseError(ex);
    		Logger.error(this, "Caught "+ex, ex);
    		ex.printStackTrace();
        	throw new IOException(ex.getMessage());
        } finally {
	    	if(c!=null) {
	    		try{c.close();}catch(DatabaseException ex2){}
	    	
	    	}
	    	if(t!=null) {
	    		try{t.abort();}catch(DatabaseException ex2){}
	    	}
        	
        }
	    	
    	return true;
	}

	/**
     * Store a block.
     */
    private synchronized void innerPut(KeyBlock block) throws IOException {   	
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
        	
        	if(chkBlocksInStore<maxChkBlocks) {
        		// Expand the store file
        		long blockNum;
        		synchronized(chkBlocksInStoreLock) {
        			blockNum = chkBlocksInStore;
        			chkBlocksInStore++;
        		}
        		long byteOffset = blockNum*(dataBlockSize+headerBlockSize);
        		StoreBlock storeBlock = new StoreBlock(blockNum);
        		DatabaseEntry blockDBE = new DatabaseEntry();
        		storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
        		chkDB.put(t,routingkeyDBE,blockDBE);
        		synchronized(chkStore) {
        			try {
        				chkStore.seek(byteOffset);
        			} catch (IOException ioe) {
        				if(byteOffset > (2*1024*1024*1024)) {
        					Logger.error(this, "Environment does not support files bigger than 2 GB?");
        					System.out.println("Environment does not support files bigger than 2 GB? (exception to follow)");
        				}
        				Logger.error(this, "Caught IOException on chkStore.seek("+byteOffset+")");
        				throw ioe;
        			}
        			chkStore.write(header);
        			chkStore.write(data);
        		}
        		t.commit();
        		t = null;
        	}else{
        		// Overwrite an other block
        		Cursor c = chkDB_accessTime.openCursor(t,null);
        		DatabaseEntry keyDBE = new DatabaseEntry();
        		DatabaseEntry dataDBE = new DatabaseEntry();
        		c.getFirst(keyDBE,dataDBE,LockMode.RMW);
        		StoreBlock oldStoreBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(dataDBE);
        		c.delete();
        		c.close();
        		// Deleted, so we can now reuse it.
        		// Because we acquired a write lock, nobody else has taken it.
        		StoreBlock storeBlock = new StoreBlock(oldStoreBlock.getOffset());
        		DatabaseEntry blockDBE = new DatabaseEntry();
        		storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
        		chkDB.put(t,routingkeyDBE,blockDBE);
        		synchronized(chkStore) {
        			chkStore.seek(storeBlock.getOffset()*(long)(dataBlockSize+headerBlockSize));
        			chkStore.write(header);
        			chkStore.write(data);
        		}
        		t.commit();
        		t = null;
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
        	if(ex instanceof IOException) throw (IOException) ex;
        	else throw new IOException(ex.getMessage());
        }
    }
    
    private synchronized void checkSecondaryDatabaseError(Throwable ex) {
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
     * Store a pubkey.
     */
    public synchronized void put(byte[] hash, DSAPublicKey key) throws IOException {
		DSAPublicKey k = fetchPubKey(hash, key, true);
		if(k == null)
			innerPut(hash, key);
    }

	/**
     * Store a block.
     */
    private void innerPut(byte[] hash, DSAPublicKey key) throws IOException {   	
    	if(closed)
    		return;
    	  	
    	byte[] routingkey = hash;
        byte[] data = key.asPaddedBytes();
        
        if(!(Arrays.equals(hash, key.asBytesHash()))) {
        	Logger.error(this, "Invalid hash!: "+HexUtil.bytesToHex(hash)+" : "+key.asBytesHash());
        }
        	
        
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
	        		long blockNum;
	        		synchronized(chkBlocksInStoreLock) {
	        			blockNum = chkBlocksInStore;
	        			chkBlocksInStore++;
	        		}
	        		
	        		long byteOffset = blockNum*(dataBlockSize+headerBlockSize);
	        		StoreBlock storeBlock = new StoreBlock(blockNum);
	        		DatabaseEntry blockDBE = new DatabaseEntry();
	    	    	storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
    	    		chkDB.put(t,routingkeyDBE,blockDBE);
	    	    	synchronized(chkStore) {
	    	    		chkStore.seek(byteOffset);
	    	    		chkStore.write(data);
	    	    	}
	    	        t.commit();
	    	        t = null;
	        	}else{
	        		// Overwrite an other block
	        		Cursor c = chkDB_accessTime.openCursor(t,null);
	        		DatabaseEntry keyDBE = new DatabaseEntry();
	        		DatabaseEntry dataDBE = new DatabaseEntry();
	        		c.getFirst(keyDBE,dataDBE,LockMode.RMW);
	        		StoreBlock oldStoreBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(dataDBE);
	        		c.delete();
	        		c.close();
	        		// Deleted, so we can now reuse it.
	        		// Because we acquired a write lock, nobody else has taken it.
	        		StoreBlock storeBlock = new StoreBlock(oldStoreBlock.getOffset());
	        		DatabaseEntry blockDBE = new DatabaseEntry();
	        		storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
	        		chkDB.put(t,routingkeyDBE,blockDBE);
	        		synchronized(chkStore) {
	        			chkStore.seek(storeBlock.getOffset()*(long)(dataBlockSize+headerBlockSize));
	        			chkStore.write(data);
	        		}
	        		t.commit();
	        		t = null;
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
        	if(ex instanceof IOException) throw (IOException) ex;
        	else throw new IOException(ex.getMessage());
        }
    }
    
    private class StoreBlock {
    	private long recentlyUsed;
    	private long offset;
    	
    	public StoreBlock(long offset) {
    		this(offset,getNewRecentlyUsed());
    	}
    	
    	public StoreBlock(long offset,long recentlyUsed) {
    		this.offset = offset;
    		this.recentlyUsed = recentlyUsed;
    	}
    	    	
   	
    	public long getRecentlyUsed() {
    		return recentlyUsed;
    	}
    	
    	public void setRecentlyUsedToZero() {
    		recentlyUsed = 0;
    	}
    	
    	public void updateRecentlyUsed() {
    		recentlyUsed = getNewRecentlyUsed();
    	}
    	
    	public long getOffset() {
    		return offset;
    	}
    }
    
    /**
     * Convert StoreBlock's to the format used by the database
     */
    private class StoreBlockTupleBinding extends TupleBinding {

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

    private class BlockNumberKeyCreator implements SecondaryKeyCreator {
    	private TupleBinding theBinding;
    	
    	public BlockNumberKeyCreator(TupleBinding theBinding1) {
    		theBinding = theBinding1;
    	}
    	
    	public boolean createSecondaryKey(SecondaryDatabase secDb,
   	    	DatabaseEntry keyEntry,
   	    	DatabaseEntry dataEntry,
   	    	DatabaseEntry resultEntry) {

   	    		StoreBlock storeblock = (StoreBlock) theBinding.entryToObject(dataEntry);
   	    		Long blockNo = new Long(storeblock.offset);
   	    		longTupleBinding.objectToEntry(blockNo, resultEntry);
   	    		return true;
   	    	}
    	
    }
    
    private class ShutdownHook extends Thread {
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
    		chkDB_blockNum.close();
    		chkDB.close();
    		environment.close();
    		Logger.minor(this, "Closing database finished.");
		}catch(Exception ex){
			Logger.error(this,"Error while closing database.",ex);
			ex.printStackTrace();
		}
    }
    
    private long countCHKBlocks() throws IOException {
    	long count = 0;
    	
    	try{
	    	Cursor c = chkDB_blockNum.openCursor(null,null);
			DatabaseEntry keyDBE = new DatabaseEntry();
			DatabaseEntry dataDBE = new DatabaseEntry();
			if(c.getLast(keyDBE,dataDBE,null)==OperationStatus.SUCCESS) {
				StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(dataDBE);
				count = storeBlock.offset;
			}
			c.close();
    	}catch(DatabaseException ex){ex.printStackTrace();}

    	count++;
    	System.out.println("Count from database: "+count);
    	
    	long oCount = chkStore.length() / (dataBlockSize + headerBlockSize);
    	
    	if(oCount > count) {
    		System.err.println("Count from file length: "+oCount);
    		return oCount;
    	}
    	
    	return count;
    }
    
    private long getMaxRecentlyUsed() {
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
    
    private long getNewRecentlyUsed() {
    	synchronized(lastRecentlyUsedSync) {
    		lastRecentlyUsed++;
    		return lastRecentlyUsed;
    	}
    }

	public void setMaxKeys(long maxStoreKeys) {
		maxChkBlocks = maxStoreKeys;
	}
}
