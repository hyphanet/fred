package freenet.store;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Vector;

import org.tanukisoftware.wrapper.WrapperManager;

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
import freenet.crypt.RandomSource;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.node.SemiOrderedShutdownHook;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SortedLongSet;

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

	private static boolean logMINOR;
	
    final int dataBlockSize;
    final int headerBlockSize;
	
	private final Environment environment;
	private final TupleBinding storeBlockTupleBinding;
	private final TupleBinding longTupleBinding;
	private final File fixSecondaryFile;
	
	private long chkBlocksInStore = 0;
	private final Object chkBlocksInStoreLock = new Object();
	private long maxChkBlocks;
	private long hits = 0;
	private long misses = 0;
	private final Database chkDB;
	private final SecondaryDatabase chkDB_accessTime;
	private final SecondaryDatabase chkDB_blockNum;
	private final RandomAccessFile chkStore;
	private final SortedLongSet freeBlocks;
	
	private long lastRecentlyUsed;
	private final Object lastRecentlyUsedSync = new Object();
	
	private boolean closed;
	private boolean reallyClosed;
	private final static byte[] dummy = new byte[0];
	
	public static BerkeleyDBFreenetStore construct(int lastVersion, File baseStoreDir, boolean isStore, 
			String suffix, long maxStoreKeys, int blockSize, int headerSize, boolean throwOnTooFewKeys, short type, Environment storeEnvironment, RandomSource random, SemiOrderedShutdownHook storeShutdownHook) throws Exception {

		/**
		 * Migration strategy:
		 * 
		 * If nothing exists, create a new database in the storeEnvironment and store files of new names.
		 * Else
		 * If the old store directories exist:
		 *      If the old store file does not exist, delete the old store directories, and create a new database in the storeEnvironment and store files of new names.
		 *      Try to load the old database.
		 *      If successful
		 *             Migrate to the new database.
		 *             Move the files.
		 *      If not successful
		 *             Reconstruct the new database from the old file.
		 *             Move the old file to the new location.
		 * 
		 */
		
		// Location of old directory.
		String oldDirName = oldTypeName(type) + (isStore ? "store" : "cache") + suffix;
		File oldDir = new File(baseStoreDir, oldDirName);
		
		File oldDBDir = new File(oldDir, "database");
		File oldStoreFile = new File(oldDir, "store");
		
		// Location of new store file
		String newStoreFileName = newTypeName(type) + suffix + '.' + (isStore ? "store" : "cache");
		File newStoreFile = new File(baseStoreDir, newStoreFileName);

		String newDBPrefix = newTypeName(type)+ '-' +(isStore ? "store" : "cache")+ '-';
		
		File newFixSecondaryFile = new File(baseStoreDir, "recreate_secondary_db-"+newStoreFileName);
		
		BerkeleyDBFreenetStore tmp;
		
		if(newStoreFile.exists()) {
			
			System.err.println("Opening database using "+newStoreFile);
			
			// Try to load new database, reconstruct it if necessary.
			// Don't need to create a new Environment, since we can use the old one.
			
			tmp = openStore(storeEnvironment, newDBPrefix, newStoreFile, newFixSecondaryFile, maxStoreKeys,
					blockSize, headerSize, throwOnTooFewKeys, false, lastVersion, type, false, storeShutdownHook);
			
		} else if(oldDir.exists() && oldStoreFile.exists()) {
			
			System.err.println("Old directory exists");
			
			File storeFile = newStoreFile;
			
			// Move old store file to new location.
			
			if(!oldStoreFile.renameTo(newStoreFile)) {
				System.err.println("Cannot move store file from "+oldStoreFile+" to "+newStoreFile);
				// Use old location for now.
				storeFile = oldStoreFile;
				// Will block deletion below.
			} else {
				System.err.println("Moved store file from "+oldStoreFile+" to "+newStoreFile);
			}
			
			if(oldDBDir.exists()) {
				
				System.err.println("Old database dir exists, migrating...");
				// Try to open old database with new store file.
				// If database is invalid, do below.
				// Otherwise, copy data from old database to new database.
				
				// Open the old store
				
				Environment oldEnv = null;
				// Initialize environment
				try {
					EnvironmentConfig envConfig = new EnvironmentConfig();
					envConfig.setAllowCreate(true);
					envConfig.setTransactional(true);
					envConfig.setTxnWriteNoSync(true);
					oldEnv = new Environment(oldDBDir, envConfig);
				} catch (DatabaseException e) {
					if(oldEnv != null)
						oldEnv.close();
					throw e;
				}
				
				// Initialize CHK database
				DatabaseConfig dbConfig = new DatabaseConfig();
				dbConfig.setAllowCreate(true);
				dbConfig.setTransactional(true);
				Database oldChkDB = oldEnv.openDatabase(null,"CHK",dbConfig);
				
				// Open the new store
				// Don't reconstruct yet
				tmp = openStore(storeEnvironment, newDBPrefix, storeFile, newFixSecondaryFile, maxStoreKeys,
							blockSize, headerSize, false, true, lastVersion, type, true, storeShutdownHook);
				
				// Migrate all tuples from old database to new database.
				migrateTuples(oldEnv, oldChkDB, tmp);
				
				oldChkDB.close();
				
				oldEnv.close();

				// Now do we need to reconstruct?
				
				if(throwOnTooFewKeys && tmp.shouldReconstruct()) {
					tmp.close(false);
					System.err.println("Attempting to reconstruct after migration...");
					WrapperManager.signalStarting(5*60*60*1000);
					
					// Reconstruct
					
					tmp = new BerkeleyDBFreenetStore(storeEnvironment, newDBPrefix, newStoreFile, newFixSecondaryFile, maxStoreKeys, blockSize, headerSize, type, true, storeShutdownHook);
				}
				
				tmp.checkForHoles(tmp.countCHKBlocksFromFile(), false);
				
				tmp.maybeShrink(true, true);
				
			} else {
				
				// No old database to worry about.
				// Reconstruct the new database from the store file which is now in the right place.
				
				tmp = openStore(storeEnvironment, newDBPrefix, storeFile, newFixSecondaryFile, maxStoreKeys,
						blockSize, headerSize, throwOnTooFewKeys, false, lastVersion, type, false, storeShutdownHook);
				
			}
			
		} else {
			
			// No new store file, no new database.
			// Start from scratch, with new store.
			
			tmp = openStore(storeEnvironment, newDBPrefix, newStoreFile, newFixSecondaryFile, maxStoreKeys,
					blockSize, headerSize, throwOnTooFewKeys, false, lastVersion, type, false, storeShutdownHook);
			
		}

		// Delete old store directory
		deleteOldStoreDir(baseStoreDir, oldDBDir, oldDir, oldDirName, random);
		
		return tmp;
	}

	private boolean shouldReconstruct() throws DatabaseException, IOException {
		long chkBlocksInDatabase = countCHKBlocksFromDatabase();
		long chkBlocksFromFile;
		try {
			chkBlocksFromFile = countCHKBlocksFromFile();
		} catch (IOException e) {
			System.err.println("Cannot determine number of blocks in file: "+e);
			e.printStackTrace();
			Logger.error(this, "Cannot determine number of blocks in file: "+e, e);
			throw e;
		}
		
		return (((chkBlocksInDatabase == 0) && (chkBlocksFromFile != 0)) ||
				(((chkBlocksInDatabase + 10) * 1.1) < chkBlocksFromFile));
	}

	private static void migrateTuples(Environment oldEnv, Database oldChkDB, BerkeleyDBFreenetStore newStore) throws DatabaseException {

		System.err.println("Migrating data from old Environment to new Environment");
		/** Reads from old database */
    	Cursor c = null;
    	/** Writes to new store */
    	Transaction t = null;
		try {
			// Read from old database
			t = newStore.environment.beginTransaction(null, null);
			//t = oldEnv.beginTransaction(null,null);
			c = oldChkDB.openCursor(null,null);
			DatabaseEntry keyDBE = new DatabaseEntry();
			DatabaseEntry blockDBE = new DatabaseEntry();
			OperationStatus opStat;
			opStat = c.getFirst(keyDBE, blockDBE, LockMode.DEFAULT);
			if(opStat == OperationStatus.NOTFOUND) {
				System.err.println("Database is empty (migrating tuples).");
				c.close();
				c = null;
				return;
			}
			if(logMINOR) Logger.minor(BerkeleyDBFreenetStore.class, "Found first key");
			int x = 0;
			while(true) {
				opStat = newStore.chkDB.putNoOverwrite(t, keyDBE, blockDBE);
				if(opStat == OperationStatus.KEYEXIST) {
					System.err.println("Duplicate key");
				} else if(opStat == OperationStatus.KEYEMPTY) {
					System.err.println("Key empty");
				} else if(opStat == OperationStatus.NOTFOUND) {
					System.err.println("Not found");
				} else if(opStat == OperationStatus.SUCCESS) {
					// It worked, cool.
				} else {
					throw new Error("Unknown OperationStatus: "+opStat);
				}
				opStat = c.getNext(keyDBE, blockDBE, LockMode.DEFAULT);
				x++;
				if(x % 512 == 0) {
					t.commit();
					t = newStore.environment.beginTransaction(null, null);
				}
				if(opStat == OperationStatus.NOTFOUND) {
					System.err.println("Completed migration.");
					return;
				}
			}
		} catch (DatabaseException e) {
			System.err.println("Caught: "+e);
			e.printStackTrace();
			Logger.error(BerkeleyDBFreenetStore.class, "Caught "+e, e);
			try {
				t.abort();
			} catch (DatabaseException e1) {
				System.err.println("Failed to abort: "+e1);
				e1.printStackTrace();
			}
			t = null;
			throw e;
		} finally {
			if(c != null) {
				try {
					c.close();
				} catch (DatabaseException e) {
					System.err.println("Cannot close cursor: "+e);
					e.printStackTrace();
				}
			}
			if(t != null) {
				try {
					t.commit();
				} catch (DatabaseException e) {
					System.err.println("Cannot close transaction: "+t);
					e.printStackTrace();
				}
			}
		}
		
	}

	private static BerkeleyDBFreenetStore openStore(Environment storeEnvironment, String newDBPrefix, File newStoreFile, 
			File newFixSecondaryFile, long maxStoreKeys, int blockSize, int headerSize, boolean throwOnTooFewKeys, 
			boolean noCheck, int lastVersion, short type, boolean wipe, SemiOrderedShutdownHook storeShutdownHook) throws Exception {
		
		try {
			if((lastVersion > 0) && (lastVersion < 852)) {
				throw new DatabaseException("Reconstructing store because started from old version");
			}
			return new BerkeleyDBFreenetStore(storeEnvironment, newDBPrefix, newStoreFile, newFixSecondaryFile, 
					maxStoreKeys, blockSize, headerSize, throwOnTooFewKeys, noCheck, wipe, storeShutdownHook);
		} catch (DatabaseException e) {
			
			System.err.println("Could not open store: "+e);
			e.printStackTrace();
			
			if(type == TYPE_SSK) {
				System.err.println("Cannot reconstruct SSK store/cache. Move the old store/cache out of the way, and report to developers.");
				throw e;
			}
			
			System.err.println("Attempting to reconstruct...");
			WrapperManager.signalStarting(5*60*60*1000);
			
			// Reconstruct
			
			return new BerkeleyDBFreenetStore(storeEnvironment, newDBPrefix, newStoreFile, newFixSecondaryFile, maxStoreKeys, blockSize, headerSize, type, noCheck, storeShutdownHook);
		}
	}

	private static void deleteOldStoreDir(File baseStoreDir, File oldDBDir, File oldDir, String oldDirName, RandomSource random) {
		if(!oldDir.exists()) return;
		System.err.println("Deleting old store dir: "+oldDir);
		// Delete
		boolean deleteFailed = false;
		if(oldDBDir.exists()) {
			File[] list = oldDBDir.listFiles();
			for(int i=0;i<list.length;i++) {
				File f = list[i];
				String name = f.getName();
				if(name.equals("je.lck") || name.endsWith(".jdb")) {
					if(!f.delete()) {
						if(f.exists()) {
							System.err.println("Failed to delete old database file "+f+" (no store file so old database worthless)");
							deleteFailed = true;
						}
					}
				} else {
					System.err.println("Did not delete unknown file "+f+" - created by user?");
					deleteFailed = true;
				}
			}
			if(!deleteFailed) {
				if(!oldDBDir.delete()) {
					System.err.println("Unable to delete database directory: "+oldDBDir+" (no store file so old database worthless)");
					deleteFailed = true;
				}
			}
		}
		if(deleteFailed) {
			// Try to rename the old directory
			File f = new File(baseStoreDir, "lost+found-"+oldDirName);
			while(f.exists()) {
				f = new File(baseStoreDir, "lost+found-"+oldDirName+ '-' +Long.toHexString(random.nextLong()));
			}
			if(!oldDir.renameTo(f)) {
				System.err.println("Unable to rename old store directory "+oldDir+" to "+f+" (would have deleted it but it has user files or is not deletable)");
			}
		} else {
			if(!oldDir.delete()) {
				System.err.println("Unable to delete old store directory "+oldDir+" (no useful data)");
			}
		}
	}

	private static String oldTypeName(short type) {
		if(type == TYPE_CHK)
			return "";
		else if(type == TYPE_SSK)
			return "ssk";
		else if(type == TYPE_PUBKEY)
			return "pubkey";
		else throw new Error("No such type "+type);
	}

	private static String newTypeName(short type) {
		if(type == TYPE_CHK)
			return "chk";
		else if(type == TYPE_SSK)
			return "ssk";
		else if(type == TYPE_PUBKEY)
			return "pubkey";
		else throw new Error("No such type "+type);
	}
	
	/**
     * Initializes database
	 * @param noCheck If true, don't check for holes etc.
	 * @param wipe If true, wipe the database first.
     * @param the directory where the store is located
	 * @throws IOException 
	 * @throws DatabaseException 
     * @throws FileNotFoundException if the dir does not exist and could not be created
     */
	public BerkeleyDBFreenetStore(Environment env, String prefix, File storeFile, File fixSecondaryFile, long maxChkBlocks, int blockSize, int headerSize, boolean throwOnTooFewKeys, boolean noCheck, boolean wipe, SemiOrderedShutdownHook storeShutdownHook) throws IOException, DatabaseException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.dataBlockSize = blockSize;
		this.headerBlockSize = headerSize;
		this.freeBlocks = new SortedLongSet();
		
		this.maxChkBlocks=maxChkBlocks;
		
		environment = env;
		
		// Initialize CHK database
		DatabaseConfig dbConfig = new DatabaseConfig();
		dbConfig.setAllowCreate(true);
		dbConfig.setTransactional(true);
		if(wipe) {
			try {
				environment.removeDatabase(null,prefix+"CHK");
			} catch (DatabaseException e) {
				Logger.error(this, "Could not remove "+prefix+"CHK", e);
			}
			try {
				environment.removeDatabase(null,prefix+"CHK_accessTime");
			} catch (DatabaseException e) {
				Logger.error(this, "Could not remove "+prefix+"CHK_accessTime", e);
			}
			try {
				environment.removeDatabase(null,prefix+"CHK_blockNum");
			} catch (DatabaseException e) {
				Logger.error(this, "Could not remove "+prefix+"CHK_blockNum", e);
			}
		}
		chkDB = environment.openDatabase(null,prefix+"CHK",dbConfig);

		this.fixSecondaryFile = fixSecondaryFile;
		if(fixSecondaryFile.exists()) {
			fixSecondaryFile.delete();
			Logger.error(this, "Recreating secondary database");
			Logger.error(this, "This may take some time...");
			System.err.println("Recreating secondary database");
			System.err.println("This may take some time...");
			try {
				environment.truncateDatabase(null, prefix+"CHK_accessTime", false);
			} catch (DatabaseException e) {
				close(false);
				throw e;
			}
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
		try {
			chkDB_accessTime = environment.openSecondaryDatabase
								(null, prefix+"CHK_accessTime", chkDB, secDbConfig);
		} catch (DatabaseException e1) {
			close(false);
			throw e1;
		}
		
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
				(null, prefix+"CHK_blockNum", chkDB, blockNoDbConfig);
		} catch (DatabaseNotFoundException e) {
			System.err.println("Migrating block db index");
			// De-dupe on keys and block numbers.
			migrate();
			System.err.println("De-duped, creating new index...");
			blockNoDbConfig.setSortedDuplicates(false);
			blockNoDbConfig.setAllowCreate(true);
			blockNoDbConfig.setAllowPopulate(true);
			blockNums = environment.openSecondaryDatabase
				(null, prefix+"CHK_blockNum", chkDB, blockNoDbConfig);
		} catch (DatabaseException e) {
			close(false);
			throw e;
		}
		
		chkDB_blockNum = blockNums;
		
		// Initialize the store file
		try {
			if(!storeFile.exists())
				storeFile.createNewFile();
			chkStore = new RandomAccessFile(storeFile,"rw");
			
			long chkBlocksInDatabase = countCHKBlocksFromDatabase();
			chkBlocksInStore = chkBlocksInDatabase;
			long chkBlocksFromFile = countCHKBlocksFromFile();
			lastRecentlyUsed = getMaxRecentlyUsed();
			
			if(((chkBlocksInStore == 0) && (chkBlocksFromFile != 0)) ||
					(((chkBlocksInStore + 10) * 1.1) < chkBlocksFromFile)) {
				if(throwOnTooFewKeys) {
					try {
						close(false);
					} catch (Throwable t) {
						Logger.error(this, "Failed to close: "+t, t);
						System.err.println("Failed to close: "+t);
						t.printStackTrace();
					}
					throw new DatabaseException("Keys in database: "+chkBlocksInStore+" but keys in file: "+chkBlocksFromFile);
				} else if(!noCheck) {
					long len = checkForHoles(chkBlocksFromFile, false);
					if(len < chkBlocksFromFile) {
						System.err.println("Truncating to "+len+" as no non-holes after that point");
						chkStore.setLength(len * (dataBlockSize + headerBlockSize));
						chkBlocksInStore = len;
					}
				}
			}
			
			chkBlocksInStore = Math.max(chkBlocksInStore, chkBlocksFromFile);
			if(logMINOR) Logger.minor(this, "Keys in store: "+chkBlocksInStore);
			System.out.println("Keys in store: db "+chkBlocksInDatabase+" file "+chkBlocksFromFile+" / max "+maxChkBlocks);
			
			if(!noCheck) {
				maybeShrink(true, true);
				chkBlocksFromFile = countCHKBlocksFromFile();
				chkBlocksInStore = Math.max(chkBlocksInStore, chkBlocksFromFile);
			}
			
//			 Add shutdownhook
			storeShutdownHook.addEarlyJob(new ShutdownHook());
		} catch (DatabaseException t) {
			Logger.error(this, "Caught "+t, t);
			close(false);
			throw t;
		} catch (IOException t) {
			Logger.error(this, "Caught "+t, t);
			close(false);
			throw t;
		}
	}

	private long checkForHoles(long blocksInFile, boolean dontTruncate) throws DatabaseException {
		System.err.println("Checking for holes in database...");
		WrapperManager.signalStarting(5*60*1000 + (int)blocksInFile*100); // 10/sec
		long holes = 0;
		long maxPresent = 0;
		freeBlocks.clear();
		for(long i=0;i<blocksInFile;i++) {
			Long blockNo = new Long(i);
			DatabaseEntry blockNumEntry = new DatabaseEntry();
			DatabaseEntry found = new DatabaseEntry();
			longTupleBinding.objectToEntry(blockNo, blockNumEntry);
			
			OperationStatus success = 
				chkDB_blockNum.get(null, blockNumEntry, found, LockMode.DEFAULT);
			
			if(success.equals(OperationStatus.NOTFOUND)) {
				addFreeBlock(i, true, "hole found");
				holes++;
			} else
				maxPresent = i;
			if(i % 1024 == 0)
			System.err.println("Checked "+i+" blocks, found "+holes+" holes");
		}
		System.err.println("Checked database, found "+holes+" holes");
		long bound = maxPresent+1;
		if(!dontTruncate) {
			if(bound < chkBlocksInStore) {
				System.err.println("Truncating to "+bound+" as no non-holes after that point");
				try {
					chkStore.setLength(bound * (dataBlockSize + headerBlockSize));
					chkBlocksInStore = bound;
					for(long l=bound;l<chkBlocksInStore;l++)
						freeBlocks.remove(l);
				} catch (IOException e) {
					Logger.error(this, "Unable to truncate!: "+e, e);
					System.err.println("Unable to truncate: "+e);
					e.printStackTrace();
				}
			}
		}
		return bound;
	}

	private void maybeShrink(boolean dontCheck, boolean offline) throws DatabaseException, IOException {
		if(chkBlocksInStore <= maxChkBlocks) return;
		if(offline)
			maybeSlowShrink(dontCheck, offline);
		else {
			if(chkBlocksInStore * 0.9 > maxChkBlocks) {
				Logger.error(this, "Doing quick and indiscriminate online shrink. Offline shrinks will preserve the LRU, this doesn't.");
				maybeQuickShrink(dontCheck, false);
			} else {
				Logger.error(this, "Online shrink only supported for small deltas because online shrink does not preserve LRU order. Suggest you restart the node.");
			}
		}
	}
	
	private void maybeSlowShrink(boolean dontCheck, boolean inStartUp) throws DatabaseException, IOException {
		Vector wantedKeep = new Vector(); // keep; content is wanted, and is in the right place
		Vector unwantedIgnore = new Vector(); // ignore; content is not wanted, and is not in the right place
		Vector wantedMove = new Vector(); // content is wanted, but is in the wrong part of the store
		Vector unwantedMove = new Vector(); // content is not wanted, but is in the part of the store we will keep
		Vector alreadyDropped = new Vector(); // any blocks past the end which have already been truncated, but which there are still database blocks pointing to
		
    	Cursor c = null;
    	Transaction t = null;

    	long newSize = maxChkBlocks;
    	if(chkBlocksInStore < maxChkBlocks) return;
    	
    	checkForHoles(maxChkBlocks, true);
    	
    	WrapperManager.signalStarting(5*60*1000 + (int)chkBlocksInStore * 100); // 10 per second
    	
    	long realSize = countCHKBlocksFromFile();
    	
    	System.err.println("Shrinking from "+chkBlocksInStore+" to "+maxChkBlocks+" (from db "+countCHKBlocksFromDatabase()+" from file "+countCHKBlocksFromFile()+ ')');
    	
    	try {
			c = chkDB_accessTime.openCursor(null,null);
			
			DatabaseEntry keyDBE = new DatabaseEntry();
			DatabaseEntry blockDBE = new DatabaseEntry();
			OperationStatus opStat;
			opStat = c.getLast(keyDBE, blockDBE, LockMode.RMW);
			
			if(opStat == OperationStatus.NOTFOUND) {
				System.err.println("Database is empty (shrinking).");
				c.close();
				c = null;
				return;
			}

			//Logger.minor(this, "Found first key");
			int x = 0;
			while(true) {
		    	StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
				long block = storeBlock.offset;
				if(storeBlock.offset > Integer.MAX_VALUE) {
					// 2^31 * blockSize; ~ 70TB for CHKs, 2TB for the others
					System.err.println("Store too big, doing quick shrink"); // memory usage would be insane
					c.close();
					c = null;
					maybeQuickShrink(false, false);
					return;
				}
				Integer blockNum = new Integer((int)storeBlock.offset);
				//Long seqNum = new Long(storeBlock.recentlyUsed);
				//System.out.println("#"+x+" seq "+seqNum+": block "+blockNum);
				if(blockNum.longValue() >= realSize) {
					// Truncated already?
					Logger.minor(this, "Truncated already? "+blockNum.longValue());
					alreadyDropped.add(blockNum);
					
				} else {
					if(x < newSize) {
						// Wanted
						if(block < newSize) {
							//System.out.println("Keep where it is: block "+blockNum+" seq # "+x+" / "+newSize);
							wantedKeep.add(blockNum);
						} else {
							//System.out.println("Move to where it should go: "+blockNum+" seq # "+x+" / "+newSize);
							wantedMove.add(blockNum);
						}
					} else {
						// Unwanted
						if(block < newSize) {
							//System.out.println("Overwrite: "+blockNum+" seq # "+x+" / "+newSize);
							unwantedMove.add(blockNum);
						} else {
							//System.out.println("Ignore, will be wiped: block "+blockNum+" seq # "+x+" / "+newSize);
							unwantedIgnore.add(blockNum);
						}
					}
					x++;
					if(x % 1024 == 0) {
						System.out.println("Reading store prior to shrink: "+(x*100/realSize)+ "% ( "+x+ '/' +realSize+ ')');
					}
					if(x == Integer.MAX_VALUE) {
						System.err.println("Key number "+x+" - ignoring store after "+(x*(dataBlockSize+headerBlockSize)+" bytes"));
						break;
					}
				}
				
				opStat = c.getPrev(keyDBE, blockDBE, LockMode.RMW);
				if(opStat == OperationStatus.NOTFOUND) {
					System.out.println("Read store: "+x+" keys.");
					break;
				}
			}
			
    	} finally {
    		if(c != null)
    			c.close();
    	}
    	
    	Integer[] wantedKeepNums = (Integer[]) wantedKeep.toArray(new Integer[wantedKeep.size()]);
    	Integer[] unwantedIgnoreNums = (Integer[]) unwantedIgnore.toArray(new Integer[unwantedIgnore.size()]);
    	Integer[] wantedMoveNums = (Integer[]) wantedMove.toArray(new Integer[wantedMove.size()]);
    	Integer[] unwantedMoveNums = (Integer[]) unwantedMove.toArray(new Integer[unwantedMove.size()]);
    	long[] freeEarlySlots = freeBlocks.toArray();
    	Arrays.sort(wantedKeepNums);
    	Arrays.sort(unwantedIgnoreNums);
    	Arrays.sort(wantedMoveNums);
    	Arrays.sort(unwantedMoveNums);
    	
    	for(int i=0;i<realSize;i++) {
    		Integer ii = new Integer(i);
    		if(Arrays.binarySearch(wantedKeepNums, ii) >= 0) continue;
    		if(Arrays.binarySearch(unwantedIgnoreNums, ii) >= 0) continue;
    		if(Arrays.binarySearch(wantedMoveNums, ii) >= 0) continue;
    		if(Arrays.binarySearch(unwantedMoveNums, ii) >= 0) continue;
    		unwantedMove.add(ii);
    	}
    	unwantedMoveNums = (Integer[]) unwantedMove.toArray(new Integer[unwantedMove.size()]);
    	
    	System.err.println("Keys to keep where they are:     "+wantedKeepNums.length);
    	System.err.println("Keys which will be wiped anyway: "+unwantedIgnoreNums.length);
    	System.err.println("Keys to move:                    "+wantedMoveNums.length);
    	System.err.println("Keys to be moved over:           "+unwantedMoveNums.length);
    	System.err.println("Free slots to be moved over:     "+freeEarlySlots.length);
    	
    	// Now move all the wantedMove blocks onto the corresponding unwantedMove's.
    	
    	WrapperManager.signalStarting(5*60*1000 + wantedMoveNums.length*1000); // 1 per second
    	
    	byte[] buf = new byte[headerBlockSize + dataBlockSize];
    	t = null;
    	try {
    	t = environment.beginTransaction(null,null);
    	if(alreadyDropped.size() > 0) {
    		System.err.println("Deleting "+alreadyDropped.size()+" blocks beyond the length of the file");
    		for(int i=0;i<alreadyDropped.size();i++) {
    			Integer unwantedBlock = (Integer) alreadyDropped.get(i);
    			DatabaseEntry unwantedBlockEntry = new DatabaseEntry();
    			longTupleBinding.objectToEntry(unwantedBlock, unwantedBlockEntry);
    			chkDB_blockNum.delete(t, unwantedBlockEntry);
    			if(i % 1024 == 0) {
    				t.commit();
    				t = environment.beginTransaction(null,null);
    			}
    		}
    		if(alreadyDropped.size() % 1024 != 0) {
    			t.commit();
    			t = environment.beginTransaction(null,null);
    		}
    	}
    	for(int i=0;i<wantedMoveNums.length;i++) {
    		Integer wantedBlock = wantedMoveNums[i];
    		
    		Integer unwantedBlock;
    		
    		// Can we move over an empty slot?
    		if(i < freeEarlySlots.length) {
    			// Don't need to delete old block
    			unwantedBlock = new Integer((int) freeEarlySlots[i]); // will fit in an int
    		} else if(unwantedMoveNums.length + freeEarlySlots.length > i) {
        		unwantedBlock = unwantedMoveNums[i-freeEarlySlots.length];
        		// Delete unwantedBlock from the store
        		DatabaseEntry unwantedBlockEntry = new DatabaseEntry();
        		longTupleBinding.objectToEntry(unwantedBlock, unwantedBlockEntry);
        		// Delete the old block from the database.
        		chkDB_blockNum.delete(t, unwantedBlockEntry);
    		} else {
    			System.err.println("Keys to move but no keys to move over! Moved "+i);
    			t.commit();
    			t = null;
    			return;
    		}
    		// Move old data to new location

    		DatabaseEntry wantedBlockEntry = new DatabaseEntry();
    		longTupleBinding.objectToEntry(wantedBlock, wantedBlockEntry);
    		long seekTo = wantedBlock.longValue() * (headerBlockSize + dataBlockSize);
    		try {
    			chkStore.seek(seekTo);
    			chkStore.readFully(buf);
    		} catch (EOFException e) {
    			System.err.println("Was reading "+wantedBlock+" to write to "+unwantedBlock);
    			System.err.println(e);
    			e.printStackTrace();
    			throw e;
    		}
    		seekTo = unwantedBlock.longValue() * (headerBlockSize + dataBlockSize);
    		chkStore.seek(seekTo);
    		chkStore.write(buf);
    		
    		// Update the database w.r.t. the old block.
    		
    		DatabaseEntry routingKeyDBE = new DatabaseEntry();
    		DatabaseEntry blockDBE = new DatabaseEntry();
    		chkDB_blockNum.get(t, wantedBlockEntry, routingKeyDBE, blockDBE, LockMode.RMW);
    		StoreBlock block = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
    		block.offset = unwantedBlock.longValue();
    		storeBlockTupleBinding.objectToEntry(block, blockDBE);
    		chkDB.put(t, routingKeyDBE, blockDBE);
    		
    		// Think about committing the transaction.
    		
    		if((i+1) % 2048 == 0) {
    			t.commit();
    			t = environment.beginTransaction(null,null);
				System.out.println("Moving blocks: "+(i*100/wantedMove.size())+ "% ( "+i+ '/' +wantedMove.size()+ ')');
    		}
    		//System.err.println("Moved "+wantedBlock+" to "+unwantedBlock);
    	}
    	System.out.println("Moved all "+wantedMove.size()+" blocks");
    	if(t != null) {
    		t.commit();
    		t = null;
    	}
    	} finally {
    		if(t != null)
    			t.abort();
    	}
    	
    	// If there are any slots left over, they must be free.
    	freeBlocks.clear();
    	for(int i=wantedMoveNums.length;i<unwantedMoveNums.length+freeEarlySlots.length;i++) {
    		if(i < freeEarlySlots.length) {
    			addFreeBlock(freeEarlySlots[i], false, "early slot "+i);
    		} else {
    			addFreeBlock(unwantedMoveNums[i-freeEarlySlots.length].longValue(), false, "unwanted "+(i-freeEarlySlots.length));
    		}
    	}
    	
    	maybeQuickShrink(false, true);
	}
	
	private void maybeQuickShrink(boolean dontCheck, boolean dontCheckForHoles) throws DatabaseException, IOException {
		Transaction t = null;
		try {
			// long's are not atomic.
			long maxBlocks;
			long curBlocks;
			synchronized(this) {
				maxBlocks = maxChkBlocks;
				curBlocks = chkBlocksInStore;
				if(maxBlocks >= curBlocks)
					return;
			}
			System.err.println("Shrinking store: "+curBlocks+" -> "+maxBlocks+" (from db "+countCHKBlocksFromDatabase()+" from file "+countCHKBlocksFromFile()+ ')');
			Logger.error(this, "Shrinking store: "+curBlocks+" -> "+maxBlocks+" (from db "+countCHKBlocksFromDatabase()+" from file "+countCHKBlocksFromFile()+ ')');
	    	WrapperManager.signalStarting((int)Math.min(0,(curBlocks-maxBlocks)*100)+5*60*1000); // 10 per second plus 5 minutes
			while(true) {
				t = environment.beginTransaction(null,null);
				long deleted = 0;
				for(long i=curBlocks-1;i>=maxBlocks;i--) {

					// Delete the block with this blocknum.
					
					Long blockNo = new Long(i);
					DatabaseEntry blockNumEntry = new DatabaseEntry();
					longTupleBinding.objectToEntry(blockNo, blockNumEntry);
					
					OperationStatus result = 
						chkDB_blockNum.delete(t, blockNumEntry);
					if(result.equals(OperationStatus.SUCCESS))
						deleted++;
					
					if((curBlocks-i) % 2048 == 0) {
						t.commit();
						if(i-1 >= maxBlocks)
							t = environment.beginTransaction(null,null);
						else
							t = null;
					}

					freeBlocks.remove(i);
					
					synchronized(this) {
						maxBlocks = maxChkBlocks;
						curBlocks = chkBlocksInStore;
						if(maxBlocks >= curBlocks) break;
					}
					
				}
				
				t.commit();
				
				System.err.println("Deleted "+deleted+" keys");
				
				t = null;
				
				if((deleted == 0) || dontCheck) break;
				else {
					System.err.println("Checking...");
					synchronized(this) {
						maxBlocks = maxChkBlocks;
						curBlocks = chkBlocksInStore;
						if(maxBlocks >= curBlocks) break;
					}
				}
			}
			
			chkStore.setLength(maxChkBlocks * (dataBlockSize + headerBlockSize));
			
			chkBlocksInStore = maxChkBlocks;
			System.err.println("Successfully shrunk store to "+chkBlocksInStore);
			
			if(!dontCheckForHoles)
				checkForHoles(chkBlocksInStore, false);
			
		} finally {
			if(t != null) t.abort();
		}
	}

	public static final short TYPE_CHK = 0;
	public static final short TYPE_PUBKEY = 1;
	public static final short TYPE_SSK = 2;
	
	/**
     * Recreate the index from the data file. Call this when the index has been corrupted.
     * @param the directory where the store is located
     * @throws FileNotFoundException if the dir does not exist and could not be created
     */
	public BerkeleyDBFreenetStore(Environment env, String prefix, File storeFile, File fixSecondaryFile, long maxChkBlocks, int blockSize, int headerSize, short type, boolean noCheck, SemiOrderedShutdownHook storeShutdownHook) throws Exception {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.dataBlockSize = blockSize;
		this.headerBlockSize = headerSize;
		this.freeBlocks = new SortedLongSet();
		this.maxChkBlocks=maxChkBlocks;
		this.environment = env;
		
		// Delete old database(s).
		try {
			environment.removeDatabase(null, prefix+"CHK");
		} catch (DatabaseException e) {
			Logger.error(this, "Could not remove old database: "+e, e);
		}
		try {
			environment.removeDatabase(null, prefix+"CHK_accessTime");
		} catch (DatabaseException e) {
			Logger.error(this, "Could not remove old database accesstime: "+e, e);
		}
		try {
			environment.removeDatabase(null, prefix+"CHK_blockNum");
		} catch (DatabaseException e) {
			Logger.error(this, "Could not remove old database blocknum: "+e, e);
		}
		
		// Initialize CHK database
		DatabaseConfig dbConfig = new DatabaseConfig();
		dbConfig.setAllowCreate(true);
		dbConfig.setTransactional(true);
		
		chkDB = environment.openDatabase(null,prefix+"CHK",dbConfig);
		
		this.fixSecondaryFile = fixSecondaryFile;
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
							(null, prefix+"CHK_accessTime", chkDB, secDbConfig);
		
		// Initialize other secondary database sorted on block number
		try {
			environment.removeDatabase(null, prefix+"CHK_blockNum");
		} catch (DatabaseNotFoundException e) { };
		SecondaryConfig blockNoDbConfig = new SecondaryConfig();
		blockNoDbConfig.setAllowCreate(true);
		blockNoDbConfig.setSortedDuplicates(false);
		blockNoDbConfig.setAllowPopulate(true);
		blockNoDbConfig.setTransactional(true);
		
		BlockNumberKeyCreator bnkc = 
			new BlockNumberKeyCreator(storeBlockTupleBinding);
		blockNoDbConfig.setKeyCreator(bnkc);
		System.err.println("Creating block db index");
		chkDB_blockNum = environment.openSecondaryDatabase
			(null, prefix+"CHK_blockNum", chkDB, blockNoDbConfig);
		
		// Initialize the store file
		if(!storeFile.exists())
			storeFile.createNewFile();
		chkStore = new RandomAccessFile(storeFile,"rw");
		
		chkBlocksInStore = 0;
		
		lastRecentlyUsed = 0;
		
		reconstruct(type);
		
		chkBlocksInStore = countCHKBlocksFromFile();
		lastRecentlyUsed = getMaxRecentlyUsed();
		
		if(!noCheck) {
			checkForHoles(chkBlocksInStore, false);
			maybeShrink(true, true);
		}
		
//		 Add shutdownhook
		storeShutdownHook.addEarlyJob(new ShutdownHook());
	}
	
	private void reconstruct(short type) throws DatabaseException {
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
							CHKBlock chk = CHKBlock.construct(data, header);
							routingkey = chk.getKey().getRoutingKey();
						} catch (CHKVerifyException e) {
							String err = "Bogus key at slot "+l+" : "+e+" - lost block "+l;
							Logger.error(this, err, e);
							System.err.println(err);
							e.printStackTrace();
							addFreeBlock(l, true, "bogus key ("+type+ ')');
							routingkey = null;
							continue;
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
					StoreBlock storeBlock = new StoreBlock(this, blockNum);
					DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
					DatabaseEntry blockDBE = new DatabaseEntry();
					storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
					chkDB.put(t,routingkeyDBE,blockDBE);
					t.commit();
					if(l % 1024 == 0)
						System.out.println("Key "+l+ '/' +(chkStore.length()/(dataBlockSize+headerBlockSize))+" OK");
					t = null;
				} finally {
					l++;
					if(t != null) t.abort();
				}
			}
		} catch (EOFException e) {
			migrate();
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
	private void migrate() throws DatabaseException {
		
		System.err.println("Migrating database: Creating unique index on block number");
		HashSet s = new HashSet();
		
    	Cursor c = null;
    	Transaction t = null;
		try {
			t = environment.beginTransaction(null,null);
			c = chkDB.openCursor(t,null);
			DatabaseEntry keyDBE = new DatabaseEntry();
			DatabaseEntry blockDBE = new DatabaseEntry();
			OperationStatus opStat;
			opStat = c.getLast(keyDBE, blockDBE, LockMode.RMW);
			if(opStat == OperationStatus.NOTFOUND) {
				System.err.println("Database is empty (migrating).");
				c.close();
				c = null;
				t.abort();
				t = null;
				return;
			}
			if(logMINOR) Logger.minor(this, "Found first key");
			int x = 0;
			while(true) {
		    	StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
		    	if(logMINOR) Logger.minor(this, "Found another key ("+(x++)+") ("+storeBlock.offset+ ')');
				Long l = new Long(storeBlock.offset);
				if(s.contains(l)) {
					if(logMINOR) Logger.minor(this, "Deleting (block number conflict).");
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
			if(c != null)
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
    			c = null;
    			t.abort();
    			t = null;
    			synchronized(this) {
    				misses++;
    			}
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
	    				if(seekTarget > (2l*1024*1024*1024)) {
	    					Logger.error(this, "Environment does not support files bigger than 2 GB?");
	    					System.out.println("Environment does not support files bigger than 2 GB? (exception to follow)");
	    				}
		    			Logger.error(this, "Caught IOException on chkStore.seek("+seekTarget+ ')');
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
		    		c = null;
		    		t.commit();
		    		t = null;
	    		}else{
	    			c.close();
	    			c = null;
	    			t.abort();
	    			t = null;
	    		}
	    		
	    		if(logMINOR) {
	    			Logger.minor(this, "Get key: "+chk);
	    			Logger.minor(this, "Headers: "+header.length+" bytes, hash "+header);
	    			Logger.minor(this, "Data: "+data.length+" bytes, hash "+data);
	    		}
	    		
	    	}catch(CHKVerifyException ex){
	    		Logger.error(this, "CHKBlock: Does not verify ("+ex+"), setting accessTime to 0 for : "+chk);
	    		System.err.println("Does not verify (CHK block "+storeBlock.offset+ ')');
	    		c.close();
	    		c = null;
	    		chkDB.delete(t, routingkeyDBE);
	    		t.commit();
	    		t = null;
	    		addFreeBlock(storeBlock.offset, true, "CHK does not verify");
	    		synchronized(this) {
	    			misses++;
	    		}
	            return null;
	    	}
	    	synchronized(this) {
	    		hits++;
	    	}
	    	return block;
    	}catch(Throwable ex) {  // FIXME: ugly  
    		if(c!=null) {
    			try{c.close();}catch(DatabaseException ex2){}
    		}
    		if(t!=null)
    			try{t.abort();}catch(DatabaseException ex2){}
    		Logger.error(this, "Caught "+ex, ex);
    		ex.printStackTrace();
           	checkSecondaryDatabaseError(ex);
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
    			c = null;
    			t.abort();
    			t = null;
    			synchronized(this) {
    				misses++;
    			}
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
	    			c = null;
		    		t.commit();
		    		t = null;
	    		}else{
	    			c.close();
	    			c = null;
	    			t.abort();
	    			t = null;
	    		}
	    		
	    		if(logMINOR) {
	    			Logger.minor(this, "Get key: "+chk);
	    			Logger.minor(this, "Headers: "+header.length+" bytes, hash "+header);
	    			Logger.minor(this, "Data: "+data.length+" bytes, hash "+data);
	    		}
	    		
	    	}catch(SSKVerifyException ex){
	    		Logger.normal(this, "SSKBlock: Does not verify ("+ex+"), setting accessTime to 0 for : "+chk, ex);
	    		c.close();
	    		c = null;
	    		chkDB.delete(t, routingkeyDBE);
	    		t.commit();
	    		t = null;
	    		addFreeBlock(storeBlock.offset, true, "SSK does not verify");
	    		synchronized(this) {
	    			misses++;
	    		}
	            return null;
	    	}
	    	synchronized(this) {
	    		hits++;
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
    			c = null;
    			t.abort();
    			t = null;
    			synchronized(this) {
    				misses++;
    			}
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
    		if(logMINOR) Logger.minor(this, "Reading from store... "+storeBlock.offset+" ("+storeBlock.recentlyUsed+ ')');
    		synchronized(chkStore) {
	    		chkStore.seek(storeBlock.offset*(long)(dataBlockSize+headerBlockSize));
	    		chkStore.readFully(data);
    		}
    		if(logMINOR) Logger.minor(this, "Read");
    		
    		try {
    			block = new DSAPublicKey(data);
    		} catch (IOException e) {
    			Logger.error(this, "Could not read key: "+e, e);
    			finishKey(storeBlock, c, t, routingkeyDBE, hash, replacement);
    		}
    		
    		if(!Arrays.equals(block.asBytesHash(), hash)) {
    			finishKey(storeBlock, c, t, routingkeyDBE, hash, replacement);
    		}
	    	// Finished, commit.
	    	c.close();
	    	c = null;
	    	t.commit();
	    	t = null;
	    	
	    	if(logMINOR) {
	    		Logger.minor(this, "Get key: "+HexUtil.bytesToHex(hash));
	    		Logger.minor(this, "Data: "+data.length+" bytes, hash "+data);
	    	}
	    	
	        synchronized(this) {
	        	hits++;
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

    private boolean finishKey(StoreBlock storeBlock, Cursor c, Transaction t, DatabaseEntry routingkeyDBE, byte[] hash, DSAPublicKey replacement) throws IOException, DatabaseException {
		if(replacement != null) {
			Logger.normal(this, "Replacing corrupt DSAPublicKey ("+HexUtil.bytesToHex(hash));
			synchronized(chkStore) {
				chkStore.seek(storeBlock.offset*(long)(dataBlockSize+headerBlockSize));
				byte[] toWrite = replacement.asPaddedBytes();
				chkStore.write(toWrite);
			}
			return true;
		} else {
			Logger.error(this, "DSAPublicKey: Does not verify (unequal hashes), setting accessTime to 0 for : "+HexUtil.bytesToHex(hash));
			c.close();
			c = null;
			chkDB.delete(t, routingkeyDBE);
			t.commit();
			t = null;
			addFreeBlock(storeBlock.offset, true, "pubkey does not verify");
			synchronized(this) {
				misses++;
			}
			return false;
		}
	}

	private void addFreeBlock(long offset, boolean loud, String reason) {
   		if(freeBlocks.push(offset)) {
   			if(loud) {
   				System.err.println("Freed block "+offset+" ("+reason+ ')');
   				Logger.normal(this, "Freed block "+offset+" ("+reason+ ')');
   			} else {
   				if(logMINOR) Logger.minor(this, "Freed block "+offset+" ("+reason+ ')');
   			}
   		} else {
   			if(logMINOR) Logger.minor(this, "Already freed block "+offset+" ("+reason+ ')');
   		}
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
    			c = null;
    			t.abort();
    			t = null;
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
        	
        	writeBlock(header, data, t, routingkeyDBE);
        	
    		t.commit();
    		t = null;
        	
    		if(logMINOR) {
    			Logger.minor(this, "Put key: "+block.getKey());
    			Logger.minor(this, "Headers: "+header.length+" bytes, hash "+Fields.hashCode(header));
    			Logger.minor(this, "Data: "+data.length+" bytes, hash "+Fields.hashCode(data));
    		}
                
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
    
    private void overwriteLRUBlock(byte[] header, byte[] data, Transaction t, DatabaseEntry routingkeyDBE) throws DatabaseException, IOException {
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
		StoreBlock storeBlock = new StoreBlock(this, oldStoreBlock.getOffset());
		DatabaseEntry blockDBE = new DatabaseEntry();
		storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
		chkDB.put(t,routingkeyDBE,blockDBE);
		synchronized(chkStore) {
			chkStore.seek(storeBlock.getOffset()*(long)(dataBlockSize+headerBlockSize));
			chkStore.write(header);
			chkStore.write(data);
		}
	}

	private boolean writeNewBlock(long blockNum, byte[] header, byte[] data, Transaction t, DatabaseEntry routingkeyDBE) throws DatabaseException, IOException {
		long byteOffset = blockNum*(dataBlockSize+headerBlockSize);
		StoreBlock storeBlock = new StoreBlock(this, blockNum);
		DatabaseEntry blockDBE = new DatabaseEntry();
		storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
		try {
			chkDB.put(t,routingkeyDBE,blockDBE);
		} catch (DatabaseException e) {
			DatabaseEntry blockNumEntry = new DatabaseEntry();
			DatabaseEntry found = new DatabaseEntry();
			longTupleBinding.objectToEntry(new Long(blockNum), blockNumEntry);
			
			OperationStatus success = 
				chkDB_blockNum.get(t, blockNumEntry, found, LockMode.DEFAULT);

			if(success == OperationStatus.KEYEXIST || success == OperationStatus.SUCCESS) {
				System.err.println("Trying to overwrite block "+blockNum+" but already used");
				return false;
			} else {
				Logger.minor(this, "Key doesn't exist for block num "+blockNum+" but caught "+e, e);
				throw e;
			}
		}
		synchronized(chkStore) {
			try {
				chkStore.seek(byteOffset);
			} catch (IOException ioe) {
				if(byteOffset > (2l*1024*1024*1024)) {
					Logger.error(this, "Environment does not support files bigger than 2 GB?");
					System.out.println("Environment does not support files bigger than 2 GB? (exception to follow)");
				}
				Logger.error(this, "Caught IOException on chkStore.seek("+byteOffset+ ')');
				throw ioe;
			}
			chkStore.write(header);
			chkStore.write(data);
		}
		return true;
	}

	private synchronized void checkSecondaryDatabaseError(Throwable ex) {
    	if((ex instanceof DatabaseException) && (ex.getMessage().indexOf("missing key in the primary database") > -1)) {
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
        	
        	writeBlock(dummy, data, t, routingkeyDBE);
        	
    		t.commit();
    		t = null;
        	
    		if(logMINOR) {
    			Logger.minor(this, "Put key: "+HexUtil.bytesToHex(hash));
    			Logger.minor(this, "Data: "+data.length+" bytes, hash "+Fields.hashCode(data));
    		}
                
        } catch(Throwable ex) {  // FIXME: ugly  
        	Logger.error(this, "Caught "+ex, ex);
        	System.err.println("Caught: "+ex);
        	ex.printStackTrace();
        	if(t!=null){
        		try{t.abort();}catch(DatabaseException ex2){};
        	}
        	checkSecondaryDatabaseError(ex);
        	if(ex instanceof IOException) throw (IOException) ex;
        	else throw new IOException(ex.getMessage());
        }
    }
    
    private void writeBlock(byte[] header, byte[] data, Transaction t, DatabaseEntry routingkeyDBE) throws DatabaseException, IOException {
    	
   		long blockNum;
   		
   		while(true) {
   	       	if((blockNum = grabFreeBlock()) >= 0) {
   	       		if(writeNewBlock(blockNum, header, data, t, routingkeyDBE))
   	       			return;
   	       	} else if(chkBlocksInStore<maxChkBlocks) {
   	       		// Expand the store file
   	       		synchronized(chkBlocksInStoreLock) {
   	       			blockNum = chkBlocksInStore;
   	       			chkBlocksInStore++;
   	       		}
   	       		// Just in case
   	       		freeBlocks.remove(blockNum);
   	       		if(writeNewBlock(blockNum, header, data, t, routingkeyDBE))
   	       			return;
   	       	}else{
   	       		overwriteLRUBlock(header, data, t, routingkeyDBE);
   	       		return;
   	       	}
   			
   		}
   		
	}

	private long grabFreeBlock() {
    	while(!freeBlocks.isEmpty()) {
    		long blockNum = freeBlocks.removeFirst();
    		if(blockNum < maxChkBlocks) return blockNum;
    	}
		return -1;
	}

	private class StoreBlock {
    	private long recentlyUsed;
    	private long offset;
    	
    	public StoreBlock(final BerkeleyDBFreenetStore bdbfs, long offset) {
    		this(offset, bdbfs.getNewRecentlyUsed());
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
    		System.err.println("Closing database due to shutdown.");
    		close(true);
    	}
    }
    
    private Object closeLock = new Object();
    
    private void close(boolean sleep) {
    	try{
			// FIXME: 	we should be sure all access to the database has stopped
			//			before we try to close it. Currently we just guess
    		//			This is nothing too problematic however since the worst thing that should
    		//			happen is that we miss the last few store()'s and get an exception.
    		logMINOR = Logger.shouldLog(Logger.MINOR, this);
    		if(logMINOR) Logger.minor(this, "Closing database "+this);
			closed=true;
			if(reallyClosed) {
				Logger.error(this, "Already closed "+this);
				return;
			}
			synchronized(closeLock) {
				if(reallyClosed) {
					Logger.error(this, "Already closed "+this);
					return;
				}
				// Give all threads some time to complete
				if(sleep)
					Thread.sleep(5000);
				try {
					if(chkStore != null)
						chkStore.close();
				} catch (Throwable t) {
					System.err.println("Caught closing database: "+t);
					t.printStackTrace();
				}
				try {
					if(chkDB_accessTime != null)
						chkDB_accessTime.close();
				} catch (Throwable t) {
					System.err.println("Caught closing database: "+t);
					t.printStackTrace();
				}
				try {
					if(chkDB_blockNum != null)
						chkDB_blockNum.close();
				} catch (Throwable t) {
					System.err.println("Caught closing database: "+t);
					t.printStackTrace();
				}
				try {	
					if(chkDB != null)
						chkDB.close();
				} catch (Throwable t) {
					System.err.println("Caught closing database: "+t);
					t.printStackTrace();
				}
				if(logMINOR) Logger.minor(this, "Closing database finished.");
				System.err.println("Closed database");
				reallyClosed = true;
			}
		}catch(Exception ex){
			Logger.error(this,"Error while closing database.",ex);
			ex.printStackTrace();
		}
    }
    
    private long countCHKBlocksFromDatabase() throws DatabaseException {
    	Cursor c = null;
    	try {
    		c = chkDB_blockNum.openCursor(null,null);
    		DatabaseEntry keyDBE = new DatabaseEntry();
    		DatabaseEntry dataDBE = new DatabaseEntry();
    		if(c.getLast(keyDBE,dataDBE,null)==OperationStatus.SUCCESS) {
    			StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(dataDBE);
    			return storeBlock.offset + 1;
    		}
    		c.close();
    		c = null;
    	} finally {
    		if(c != null) {
    			try {
    				c.close();
    			} catch (DatabaseException e) {
    				Logger.error(this, "Caught "+e, e);
    			}
    		}
    	}
		return 0;
    }
    
	private long countCHKBlocksFromFile() throws IOException {
		int keySize = headerBlockSize + dataBlockSize;
		long fileSize = chkStore.length();
		return fileSize / keySize;
	}

    private long getMaxRecentlyUsed() {
    	long maxRecentlyUsed = 0;
    	
    	Cursor c = null;
    	try{
	    	c = chkDB_accessTime.openCursor(null,null);
			DatabaseEntry keyDBE = new DatabaseEntry();
			DatabaseEntry dataDBE = new DatabaseEntry();
			if(c.getLast(keyDBE,dataDBE,null)==OperationStatus.SUCCESS) {
				StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(dataDBE);
				maxRecentlyUsed = storeBlock.getRecentlyUsed();
			}
			c.close();
			c = null;
    	} catch(DatabaseException ex) {
    		ex.printStackTrace();
    	} finally {
    		if(c != null) {
    			try {
    				c.close();
    			} catch (DatabaseException e) {
    				Logger.error(this, "Caught "+e, e);
    			}
    		}
    	}
    	
    	return maxRecentlyUsed;
    }
    
    private long getNewRecentlyUsed() {
    	synchronized(lastRecentlyUsedSync) {
    		lastRecentlyUsed++;
    		return lastRecentlyUsed;
    	}
    }

	public void setMaxKeys(long maxStoreKeys, boolean shrinkNow) throws DatabaseException, IOException {
		synchronized(this) {
			maxChkBlocks = maxStoreKeys;
		}
		if(shrinkNow)
			maybeShrink(false, false);
	}
    
    public long getMaxKeys() {
        return maxChkBlocks;
    }

	public long hits() {
		return hits;
	}
	
	public long misses() {
		return misses;
	}

	public long keyCount() {
		return chkBlocksInStore;
	}
}
