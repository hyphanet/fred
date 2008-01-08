package freenet.store;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Vector;

import org.tanukisoftware.wrapper.WrapperManager;

import com.sleepycat.bind.tuple.LongBinding;
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
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.RunRecoveryException;
import com.sleepycat.je.SecondaryConfig;
import com.sleepycat.je.SecondaryDatabase;
import com.sleepycat.je.SecondaryKeyCreator;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.log.DbChecksumException;
import com.sleepycat.je.log.LogFileNotFoundException;
import com.sleepycat.je.util.DbLoad;

import freenet.crypt.RandomSource;
import freenet.keys.KeyVerifyException;
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
* @author amphibian
*/
public class BerkeleyDBFreenetStore implements FreenetStore {

	private static boolean logMINOR;
	private static boolean logDEBUG;
	
	// If we get a DbChecksumException, create this file.
	final File reconstructFile;
	final int dataBlockSize;
	final int headerBlockSize;

	private final short storeType;
	private final Environment environment;
	private final TupleBinding storeBlockTupleBinding;
	private final File fixSecondaryFile;
	
	private long blocksInStore = 0;
	private final Object blocksInStoreLock = new Object();
	private long maxBlocksInStore;
	private long hits = 0;
	private long misses = 0;
	private long writes = 0;
	private final int keyLength;
	private final Database keysDB;
	private final SecondaryDatabase accessTimeDB;
	private final SecondaryDatabase blockNumDB;
	private final RandomAccessFile storeRAF;
	private final RandomAccessFile keysRAF;
	private final RandomAccessFile lruRAF;
	private final SortedLongSet freeBlocks;
	private final String name;
	/** Callback which translates records to blocks and back, specifies the size of blocks etc. */
	private final StoreCallback callback;
	private final boolean collisionPossible;
	
	private long lastRecentlyUsed;
	private final Object lastRecentlyUsedSync = new Object();
	
	private boolean closed;
	private boolean reallyClosed;
	
	public static String getName(boolean isStore, short type) {
		String newDBPrefix = typeName(type)+ '-' +(isStore ? "store" : "cache")+ '-';
		return newDBPrefix + "CHK";
	}
	
	public static File getFile(boolean isStore, short type, File baseStoreDir, String suffix) {
		String newStoreFileName = typeName(type) + suffix + '.' + (isStore ? "store" : "cache");
		return new File(baseStoreDir, newStoreFileName);
	}
	
	public static BerkeleyDBFreenetStore construct(int lastVersion, File baseStoreDir, boolean isStore,
			String suffix, long maxStoreKeys, 
			short type, Environment storeEnvironment, RandomSource random, 
			SemiOrderedShutdownHook storeShutdownHook, boolean tryDbLoad, File reconstructFile, StoreCallback callback) throws DatabaseException, IOException {

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
		
		// Location of new store file
		String newStoreFileName = typeName(type) + suffix + '.' + (isStore ? "store" : "cache");
		File newStoreFile = new File(baseStoreDir, newStoreFileName);
		File lruFile = new File(baseStoreDir, newStoreFileName+".lru");
		
		File keysFile;
		if(callback.storeFullKeys()) {
			keysFile = new File(baseStoreDir, newStoreFileName+".keys");
		} else {
			keysFile = null;
		}
		
		String newDBPrefix = typeName(type)+ '-' +(isStore ? "store" : "cache")+ '-';
		
		File newFixSecondaryFile = new File(baseStoreDir, "recreate_secondary_db-"+newStoreFileName);
		
		BerkeleyDBFreenetStore tmp;
		
		if(newStoreFile.exists()) {
			
			System.err.println("Opening database using "+newStoreFile);
			
			// Try to load new database, reconstruct it if necessary.
			// Don't need to create a new Environment, since we can use the old one.
			
			tmp = openStore(storeEnvironment, baseStoreDir, newDBPrefix, newStoreFile, lruFile, keysFile, newFixSecondaryFile, maxStoreKeys,
					false, lastVersion, type, false, storeShutdownHook, tryDbLoad, reconstructFile, callback);
			
		} else {
			
			// No new store file, no new database.
			// Start from scratch, with new store.
			
			tmp = openStore(storeEnvironment, baseStoreDir, newDBPrefix, newStoreFile, lruFile, keysFile, newFixSecondaryFile, 
					maxStoreKeys, false, lastVersion, type, 
					false, storeShutdownHook, tryDbLoad, reconstructFile, callback);
			
		}

		return tmp;
	}

	private static BerkeleyDBFreenetStore openStore(Environment storeEnvironment, File baseDir, String newDBPrefix, File newStoreFile,
			File lruFile, File keysFile, File newFixSecondaryFile, long maxStoreKeys, 
			boolean noCheck, int lastVersion, short type, boolean wipe, SemiOrderedShutdownHook storeShutdownHook, 
			boolean tryDbLoad, File reconstructFile, StoreCallback callback) throws DatabaseException, IOException {
		
		if(tryDbLoad) {
			String dbName = newDBPrefix+"CHK";
			File dumpFilename = new File(baseDir, dbName+".dump");
			System.err.println("Trying to restore from "+dumpFilename);
			try {
				FileInputStream fis = new FileInputStream(dumpFilename);
				// DbDump used the default charset, so will this.
				BufferedReader br = new BufferedReader(new InputStreamReader(fis));
				DbLoad loader = new DbLoad();
				loader.setEnv(storeEnvironment);
				loader.setDbName(dbName);
				loader.setInputReader(br);
				loader.setNoOverwrite(false);
				loader.setTextFileMode(false);
				loader.load();
				fis.close();
				newFixSecondaryFile.createNewFile(); // force reconstruct of secondary indexes
			} catch (IOException e) {
				System.err.println("Failed to reload database "+dbName+": "+e);
				e.printStackTrace();
			}
			
			// Should just open now, although it will need to reconstruct the secondary indexes.
		}
		
		try {
			// First try just opening it.
			return new BerkeleyDBFreenetStore(type, storeEnvironment, newDBPrefix, newStoreFile, lruFile, keysFile, newFixSecondaryFile,
					maxStoreKeys, noCheck, wipe, storeShutdownHook, 
					reconstructFile, callback);
		} catch (DatabaseException e) {
			
			// Try a reconstruct
			
			System.err.println("Could not open store: "+e);
			e.printStackTrace();
			
			System.err.println("Attempting to reconstruct index...");
			WrapperManager.signalStarting(5*60*60*1000);
			
			// Reconstruct
			
			return new BerkeleyDBFreenetStore(storeEnvironment, newDBPrefix, newStoreFile, lruFile, keysFile, newFixSecondaryFile, 
					maxStoreKeys, type, noCheck, storeShutdownHook, reconstructFile, callback);
		}
	}

	private static String typeName(short type) {
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
	 * @param reconstructFile 
	* @param the directory where the store is located
	* @throws IOException
	* @throws DatabaseException
	* @throws FileNotFoundException if the dir does not exist and could not be created
	*/
	private BerkeleyDBFreenetStore(short type, Environment env, String prefix, File storeFile, File lruFile, File keysFile, File fixSecondaryFile, long maxChkBlocks, boolean noCheck, boolean wipe, SemiOrderedShutdownHook storeShutdownHook, File reconstructFile, StoreCallback callback) throws IOException, DatabaseException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
		this.callback = callback;
		this.collisionPossible = callback.collisionPossible();
		this.dataBlockSize = callback.dataLength();
		this.headerBlockSize = callback.headerLength();
		this.keyLength = callback.fullKeyLength();
		callback.setStore(this);
		this.storeType = type;
		this.freeBlocks = new SortedLongSet();
		name = prefix;
		
		this.maxBlocksInStore=maxChkBlocks;
		this.reconstructFile = reconstructFile;
		
		environment = env;
		
		// Initialize CHK database
		DatabaseConfig dbConfig = new DatabaseConfig();
		dbConfig.setAllowCreate(true);
		dbConfig.setTransactional(true);
		if(wipe) {
			System.err.println("Wiping old database for "+prefix);
			wipeOldDatabases(environment, prefix);
		}
		
		keysDB = environment.openDatabase(null,prefix+"CHK",dbConfig);
		System.err.println("Opened main database for "+prefix);

		this.fixSecondaryFile = fixSecondaryFile;
		if(fixSecondaryFile.exists()) {
			fixSecondaryFile.delete();
			Logger.error(this, "Recreating secondary databases");
			Logger.error(this, "This may take some time...");
			System.err.println("Recreating secondary databases");
			System.err.println("This may take some time...");
			WrapperManager.signalStarting((int)(Math.min(Integer.MAX_VALUE, 5*60*1000+keysDB.count()*100)));
			// Of course it's not a solution but a quick fix
			// Integer.MAX_VALUE seems to trigger an overflow or whatever ...
			// Either we find out what the maximum value is and we do a static method somewhere ensuring
			// it won't overflow ... or we debug the wrapper.
			// NB: it might be a wrapper-version-missmatch problem (nextgens)
			try {
				try {
					environment.removeDatabase(null, prefix+"CHK_accessTime");
				} catch (DatabaseNotFoundException e) {
					// Cool!
				}
				try {
					environment.removeDatabase(null, prefix+"CHK_blockNum");
				} catch (DatabaseNotFoundException e) {
					// Cool!
				}
			} catch (DatabaseException e) {
				close(false);
				throw e;
			}
		}
		
		// Initialize secondary CHK database sorted on accesstime
		SecondaryDatabase atime = null;
		SecondaryConfig secDbConfig = new SecondaryConfig();
		secDbConfig.setAllowCreate(keysDB.count() == 0);
		secDbConfig.setSortedDuplicates(true);
		secDbConfig.setTransactional(true);
		secDbConfig.setAllowPopulate(false);
		storeBlockTupleBinding = new StoreBlockTupleBinding();
		AccessTimeKeyCreator accessTimeKeyCreator =
			new AccessTimeKeyCreator(storeBlockTupleBinding);
		secDbConfig.setKeyCreator(accessTimeKeyCreator);
		try {
			try {
				System.err.println("Opening access times database for "+prefix);
				atime = environment.openSecondaryDatabase
									(null, prefix+"CHK_accessTime", keysDB, secDbConfig);
				// The below is too slow to be useful, because SecondaryDatabase.count() isn't optimised.
//				long chkDBCount = keysDB.count();
//				System.err.println("Counting size of access times database...");
//				long atimeCount = atime.count();
//				if(atimeCount < chkDBCount) {
//					System.err.println("Access times database: "+atimeCount+" but main database: "+chkDBCount);
//					throw new DatabaseException("Needs repopulation");
//				}
			} catch (DatabaseException e) {
				WrapperManager.signalStarting((int)(Math.min(Integer.MAX_VALUE, 5*60*1000L+keysDB.count()*100L)));
				// Of course it's not a solution but a quick fix
				// Integer.MAX_VALUE seems to trigger an overflow or whatever ...
				// Either we find out what the maximum value is and we do a static method somewhere ensuring
				// it won't overflow ... or we debug the wrapper.
				// NB: it might be a wrapper-version-missmatch problem (nextgens)
				System.err.println("Reconstructing access times index...");
				Logger.error(this, "Reconstructing access times index...");
				if(atime != null) atime.close();
				try {
					environment.removeDatabase(null, prefix+"CHK_accessTime");
				} catch (DatabaseNotFoundException e1) {
					// Ok
				}
				secDbConfig.setAllowCreate(true);
				secDbConfig.setAllowPopulate(true);
				atime = environment.openSecondaryDatabase
									(null, prefix+"CHK_accessTime", keysDB, secDbConfig);
			}
		} catch (DatabaseException e1) {
			// Log this now because close() will probably throw too
			System.err.println("Error opening access times db: "+e1);
			e1.printStackTrace();
			Logger.error(this, "Error opening access times db: "+e1, e1);
			close(false);
			throw e1;
		}
		accessTimeDB = atime;
		System.err.println("Opened access times database for "+prefix);
		
		// Initialize other secondary database sorted on block number
//		try {
//			environment.removeDatabase(null, "CHK_blockNum");
//		} catch (DatabaseNotFoundException e) { };
		SecondaryConfig blockNoDbConfig = new SecondaryConfig();
		blockNoDbConfig.setAllowCreate(keysDB.count() == 0);
		blockNoDbConfig.setSortedDuplicates(false);
		blockNoDbConfig.setAllowPopulate(false);
		blockNoDbConfig.setTransactional(true);
		
		BlockNumberKeyCreator bnkc =
			new BlockNumberKeyCreator(storeBlockTupleBinding);
		blockNoDbConfig.setKeyCreator(bnkc);
		SecondaryDatabase blockNums = null;
		String blockDBName = prefix+"CHK_blockNum";
		try {
			try {
				System.err.println("Opening block db index");
				blockNums = environment.openSecondaryDatabase
					(null, blockDBName, keysDB, blockNoDbConfig);
				// The below is too slow to be useful, because SecondaryDatabase.count() isn't optimised.
//				long blockNumsCount = blockNums.count();
//				long chkDBCount = keysDB.count();
//				if(blockNumsCount < chkDBCount) {
//					System.err.println("Block nums database: "+blockNumsCount+" but main database: "+chkDBCount);
//					throw new DatabaseException("Needs repopulation");
//				}
			} catch (DatabaseException e) {
				WrapperManager.signalStarting((int)(Math.min(Integer.MAX_VALUE, 5*60*1000+keysDB.count()*100)));
				// Of course it's not a solution but a quick fix
				// Integer.MAX_VALUE seems to trigger an overflow or whatever ...
				// Either we find out what the maximum value is and we do a static method somewhere ensuring
				// it won't overflow ... or we debug the wrapper.
				// NB: it might be a wrapper-version-missmatch problem (nextgens)
				if(blockNums != null) {
					Logger.normal(this, "Closing database "+blockDBName);
					blockNums.close();
				}
				try {
					environment.removeDatabase(null, blockDBName);
				} catch (DatabaseNotFoundException e1) {
					// Ignore
					System.err.println("Database "+blockDBName+" does not exist deleting it");
				}
				System.err.println("Reconstructing block numbers index... ("+e+")");
				Logger.error(this, "Reconstructing block numbers index...", e);
				System.err.println("Creating new block DB index");
				blockNoDbConfig.setSortedDuplicates(false);
				blockNoDbConfig.setAllowCreate(true);
				blockNoDbConfig.setAllowPopulate(true);
				blockNums = environment.openSecondaryDatabase
					(null, blockDBName, keysDB, blockNoDbConfig);
			}
		} catch (DatabaseException e1) {
			// Log this now because close() will probably throw too
			System.err.println("Error opening block nums db: "+e1);
			e1.printStackTrace();
			Logger.error(this, "Error opening block nums db: "+e1, e1);
			close(false);
			throw e1;
		}
		System.err.println("Opened block number database for "+prefix);
		
		blockNumDB = blockNums;
		
		// Initialize the store file
		try {
			if(!storeFile.exists())
				if(!storeFile.createNewFile())
					throw new DatabaseException("can't create a new file "+storeFile+" !");
			storeRAF = new RandomAccessFile(storeFile,"rw");
			
			if(!lruFile.exists())
				if(!lruFile.createNewFile())
					throw new DatabaseException("can't create a new file "+lruFile+" !");
			lruRAF = new RandomAccessFile(lruFile,"rw");
			
			if(keysFile != null) {
				if(!keysFile.exists())
					if(!keysFile.createNewFile())
						throw new DatabaseException("can't create a new file "+keysFile+" !");
				keysRAF = new RandomAccessFile(keysFile,"rw");
			} else keysRAF = null;
			
			boolean dontCheckForHolesShrinking = false;
			
			long chkBlocksInDatabase = highestBlockNumberInDatabase();
			blocksInStore = chkBlocksInDatabase;
			long chkBlocksFromFile = countCHKBlocksFromFile();
			lastRecentlyUsed = getMaxRecentlyUsed();

			System.out.println("Keys in store: db "+chkBlocksInDatabase+" file "+chkBlocksFromFile+" / max "+maxChkBlocks);
			
			if(chkBlocksInDatabase > chkBlocksFromFile) {
				System.out.println("More keys in database than in store!");
				
				//throw new DatabaseException("More keys in database than in store!");
				// FIXME reinstate if handling code doesn't work
				// FIXME we can do a cleverer recovery: Find all keys whose block number is > chkBlocksFromFile and delete them
			}
			
			if(((blocksInStore == 0) && (chkBlocksFromFile != 0)) ||
					(((blocksInStore + 10) * 1.1) < chkBlocksFromFile)) {
				try {
					close(false);
				} catch (Throwable t) {
					Logger.error(this, "Failed to close: "+t, t);
					System.err.println("Failed to close: "+t);
					t.printStackTrace();
				}
				throw new DatabaseException("Keys in database: "+blocksInStore+" but keys in file: "+chkBlocksFromFile);
			}
			
			blocksInStore = Math.max(blocksInStore, chkBlocksFromFile);
			if(logMINOR) Logger.minor(this, "Keys in store: "+blocksInStore);
			
			if(!noCheck) {
				maybeOfflineShrink(dontCheckForHolesShrinking);
				chkBlocksFromFile = countCHKBlocksFromFile();
				blocksInStore = Math.max(blocksInStore, chkBlocksFromFile);
			}
			
			// Add shutdownhook
			storeShutdownHook.addEarlyJob(new ShutdownHook());
		} catch (DatabaseException t) {
			System.err.println("Caught exception, closing database: "+t);
			t.printStackTrace();
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
		System.err.println("Checking for holes in database... "+blocksInFile+" blocks in file");
		WrapperManager.signalStarting((int)Math.min(Integer.MAX_VALUE, 5*60*1000 + blocksInFile*100)); // 10/sec
		long holes = 0;
		long maxPresent = 0;
		freeBlocks.clear();
		for(long i=0;i<blocksInFile;i++) {
			DatabaseEntry blockNumEntry = new DatabaseEntry();
			DatabaseEntry found = new DatabaseEntry();
			LongBinding.longToEntry(i, blockNumEntry);
			
			OperationStatus success =
				blockNumDB.get(null, blockNumEntry, found, LockMode.DEFAULT);
			
			if(success.equals(OperationStatus.NOTFOUND)) {
				addFreeBlock(i, true, "hole found");
				holes++;
			} else
				maxPresent = i;
			if(i % 1024 == 0)
			System.err.println("Checked "+i+" blocks, found "+holes+" holes");
		}
		System.err.println("Checked database of "+blocksInFile+" blocks, found "+holes+" holes, maximum non-hole block: "+maxPresent);
		long bound = maxPresent+1;
		if(!dontTruncate) {
			if(bound < blocksInStore) {
				System.err.println("Truncating to "+bound+" as no non-holes after that point");
				try {
					storeRAF.setLength(bound * (dataBlockSize + headerBlockSize));
					lruRAF.setLength(bound * 8);
					if(keysRAF != null)
						keysRAF.setLength(bound * keyLength);
					blocksInStore = bound;
					for(long l=bound;l<blocksInStore;l++)
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

	private Object shrinkLock = new Object();
	private boolean shrinking = false;
	
	/**
	* Do an offline shrink, if necessary. Will not return until completed.
	* @param dontCheckForHoles If true, don't check for holes.
	* @throws DatabaseException
	* @throws IOException
	*/
	private void maybeOfflineShrink(boolean dontCheckForHoles) throws DatabaseException, IOException {
		if(blocksInStore <= maxBlocksInStore) return;
		maybeSlowShrink(dontCheckForHoles, true);
	}

	/**
	* Do an online shrink, if necessary. Non-blocking i.e. it will do the shrink on another thread.
	* @param forceBigOnlineShrinks If true, force the node to shrink the store immediately even if
	* it is a major (more than 10%) shrink. Normally this is not allowed because online shrinks do not
	* preserve the most recently used data; the best thing to do is to restart the node and let it do
	* an offline shrink.
	* @throws DatabaseException If a database error occurs.
	* @throws IOException If an I/O error occurs.
	* @return True if the database will be shrunk in the background (or the database is already small
	* enough), false if it is not possible to shrink it because a large shrink was requested and we
	* don't want to do a large online shrink.
	*/
	private boolean maybeOnlineShrink(boolean forceBigOnlineShrinks) throws DatabaseException, IOException {
		synchronized(this) {
			if(blocksInStore <= maxBlocksInStore) return true;
		}
		if(blocksInStore * 0.9 > maxBlocksInStore || forceBigOnlineShrinks) {
			Logger.error(this, "Doing quick and dirty shrink of the store by "+(100 * (blocksInStore - maxBlocksInStore) / blocksInStore)+"%");
			Logger.error(this, "Offline shrinks will preserve the most recently used data, this online shrink does not.");
			Runnable r = new Runnable() {
				public void run() {
					try {
						synchronized(shrinkLock) { if(shrinking) return; shrinking = true; };
						maybeQuickShrink(false);
					} catch (Throwable t) {
						Logger.error(this, "Online shrink failed: "+t, t);
					} finally {
						synchronized(shrinkLock) { shrinking = false; };
					}
				}
			};
			Thread t = new Thread(r);
			t.setDaemon(true);
			t.start();
			return true;
		} else return false;
	}
	
	private void maybeSlowShrink(boolean dontCheckForHoles, boolean inStartUp) throws DatabaseException, IOException {
		Vector wantedKeep = new Vector(); // keep; content is wanted, and is in the right place
		Vector unwantedIgnore = new Vector(); // ignore; content is not wanted, and is not in the right place
		Vector wantedMove = new Vector(); // content is wanted, but is in the wrong part of the store
		Vector unwantedMove = new Vector(); // content is not wanted, but is in the part of the store we will keep
		Vector alreadyDropped = new Vector(); // any blocks past the end which have already been truncated, but which there are still database blocks pointing to
		
		Cursor c = null;
		Transaction t = null;

		long newSize = maxBlocksInStore;
		if(blocksInStore < maxBlocksInStore) return;
		
		System.err.println("Shrinking from "+blocksInStore+" to "+maxBlocksInStore+" (from db "+keysDB.count()+" from file "+countCHKBlocksFromFile()+ ')');
		
		if(!dontCheckForHoles)
			checkForHoles(maxBlocksInStore, true);
		
		WrapperManager.signalStarting((int)(Math.min(Integer.MAX_VALUE, 5*60*1000 + blocksInStore * 100))); // 10 per second
		
		long realSize = countCHKBlocksFromFile();
		
		long highestBlock = 0;
		
		try {
			c = accessTimeDB.openCursor(null,null);
			
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
				if(block > highestBlock) highestBlock = block;
				if(storeBlock.offset > Integer.MAX_VALUE) {
					// 2^31 * blockSize; ~ 70TB for CHKs, 2TB for the others
					System.err.println("Store too big, doing quick shrink"); // memory usage would be insane
					c.close();
					c = null;
					maybeQuickShrink(true);
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
		
		for(int i=0;i<newSize;i++) {
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
		
		WrapperManager.signalStarting((int)Math.min(Integer.MAX_VALUE, (5*60*1000 + wantedMoveNums.length*1000L + alreadyDropped.size() * 100L))); // 1 per second
		
		byte[] buf = new byte[headerBlockSize + dataBlockSize];
		long lruValue;
		byte[] keyBuf = new byte[keyLength];
		t = null;
		try {
			t = environment.beginTransaction(null,null);
			if(alreadyDropped.size() > 0) {
				System.err.println("Deleting "+alreadyDropped.size()+" blocks beyond the length of the file");
				for(int i=0;i<alreadyDropped.size();i++) {
					int unwantedBlock = ((Integer) alreadyDropped.get(i)).intValue();
					DatabaseEntry unwantedBlockEntry = new DatabaseEntry();
					LongBinding.longToEntry(unwantedBlock, unwantedBlockEntry);
					blockNumDB.delete(t, unwantedBlockEntry);
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
					LongBinding.longToEntry(unwantedBlock.longValue(), unwantedBlockEntry);
					// Delete the old block from the database.
					blockNumDB.delete(t, unwantedBlockEntry);
				} else {
					System.err.println("Keys to move but no keys to move over! Moved "+i);
					t.commit();
					t = null;
					return;
				}
				// Move old data to new location
				
				DatabaseEntry wantedBlockEntry = new DatabaseEntry();
				LongBinding.longToEntry(wantedBlock.longValue(), wantedBlockEntry);
				long entry = wantedBlock.longValue();
				boolean readLRU = false;
				boolean readKey = false;
				try {
					storeRAF.seek(entry * (headerBlockSize + dataBlockSize));
					storeRAF.readFully(buf);
					lruValue = 0;
					if(lruRAF.length() > ((entry + 1) * 8)) {
						readLRU = true;
						lruRAF.seek(entry * 8);
						lruValue = lruRAF.readLong();
					}
					if(keysRAF != null && keysRAF.length() > ((entry + 1) * keyLength)) {
						readKey = true;
						keysRAF.seek(entry * keyLength);
						keysRAF.readFully(keyBuf);
					}
				} catch (EOFException e) {
					System.err.println("Was reading "+wantedBlock+" to write to "+unwantedBlock);
					System.err.println(e);
					e.printStackTrace();
					throw e;
				}
				entry = unwantedBlock.longValue();
				storeRAF.seek(entry * (headerBlockSize + dataBlockSize));
				storeRAF.write(buf);
				if(readLRU) {
					lruRAF.seek(entry * 8);
					lruRAF.writeLong(lruValue);
				}
				if(readKey) {
					keysRAF.seek(entry * keyLength);
					keysRAF.write(keyBuf);
				}
				
				// Update the database w.r.t. the old block.
				
				DatabaseEntry routingKeyDBE = new DatabaseEntry();
				DatabaseEntry blockDBE = new DatabaseEntry();
				blockNumDB.get(t, wantedBlockEntry, routingKeyDBE, blockDBE, LockMode.RMW);
				StoreBlock block = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
				block.offset = unwantedBlock.longValue();
				storeBlockTupleBinding.objectToEntry(block, blockDBE);
				keysDB.put(t, routingKeyDBE, blockDBE);
				
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
			t = null;
		}
		System.out.println("Completing shrink"); // FIXME remove
		
		int totalUnwantedBlocks = unwantedMoveNums.length+freeEarlySlots.length;
		WrapperManager.signalStarting((int)Math.min(Integer.MAX_VALUE, 5*60*1000 + (totalUnwantedBlocks-wantedMoveNums.length) * 100));
		// If there are any slots left over, they must be free.
		freeBlocks.clear();
		t = environment.beginTransaction(null,null);
		for(int i=wantedMoveNums.length;i<totalUnwantedBlocks;i++) {
			long blockNo;
			String reason;
			if(i < freeEarlySlots.length) {
				blockNo = freeEarlySlots[i];
				reason = "early slot "+i;
			} else {
				blockNo = unwantedMoveNums[i-freeEarlySlots.length].longValue();
				reason = "unwanted "+(i-freeEarlySlots.length);
			}
			DatabaseEntry unwantedBlockEntry = new DatabaseEntry();
			LongBinding.longToEntry(blockNo, unwantedBlockEntry);
			blockNumDB.delete(t, unwantedBlockEntry);
			if(i % 1024 == 0) {
				System.out.println("Trimmed surplus keys in database: "+(i-wantedMoveNums.length)+"/"+(totalUnwantedBlocks-wantedMoveNums.length));
				t.commit();
				if(i == totalUnwantedBlocks-1)
					t = null;
				else
					t = environment.beginTransaction(null,null);
			}
			addFreeBlock(blockNo, true, reason);
		}
		if(t != null) t.commit();
		t = null;
		
		System.out.println("Finishing shrink"); // FIXME remove
		
		storeRAF.setLength(newSize * (dataBlockSize + headerBlockSize));
		lruRAF.setLength(newSize * 8);
		if(keysRAF != null)
			keysRAF.setLength(newSize * keyLength);
		
		synchronized(this) {
			blocksInStore = newSize;
		}
		System.err.println("Shrunk store, now have "+blocksInStore+" of "+maxBlocksInStore);
	}
	
	/**
	* Shrink the store, on the fly/quickly.
	* @param offline If false, keep going until the store has shrunk enough.
	* @throws DatabaseException
	* @throws IOException
	*/
	private void maybeQuickShrink(boolean offline) throws DatabaseException, IOException {
		// long's are not atomic.
		long maxBlocks;
		long curBlocks;
		synchronized(this) {
			maxBlocks = maxBlocksInStore;
			curBlocks = blocksInStore;
			if(maxBlocks >= curBlocks) {
				System.out.println("Not shrinking store: "+curBlocks+" < "+maxBlocks);
				return;
			}
		}
		innerQuickShrink(curBlocks, maxBlocks, offline);
	}

	/**
	* @param curBlocks The current number of blocks in the file. (From the file length).
	* @param maxBlocks The target number of blocks in the file. (The file will be truncated to this length in blocks).
	* @param offline If true, innerQuickShrink will run once. If false, after the first run, if
	* the store is still over its required size, it will shrink it again, and so on until the store
	* is within its required size.
	* If false, innerQuickShrink will repeat itself until it deletes no more blocks, This is to handle
	* @throws DatabaseException If a database error occurs.
	* @throws IOException If an I/O error occurs.
	*/
	private void innerQuickShrink(long curBlocks, long maxBlocks, boolean offline) throws DatabaseException, IOException {
		long oldCurBlocks = curBlocks;
		try {
			curBlocks = Math.max(oldCurBlocks, highestBlockNumberInDatabase());
		} catch (DatabaseException e) {
			Logger.error(this, "Ignoring "+e+" in innerQuickShrink initialisation", e);
		}
		Transaction t = null;
		try {
			String msg = "Shrinking store: "+curBlocks+" -> "+maxBlocks+" (from db "+keysDB.count()+", highest "+highestBlockNumberInDatabase()+", from file "+countCHKBlocksFromFile()+ ')';
			System.err.println(msg); Logger.normal(this, msg);
			WrapperManager.signalStarting((int)Math.min(Integer.MAX_VALUE, (5*60*1000 + 100 * (Math.max(0, curBlocks-maxBlocks)))));
			while(true) {
				t = environment.beginTransaction(null,null);
				long deleted = 0;
				for(long i=curBlocks-1;i>=maxBlocks;i--) {

					if(t == null)
						t = environment.beginTransaction(null,null);
					
					// Delete the block with this blocknum.
					
					DatabaseEntry blockNumEntry = new DatabaseEntry();
					LongBinding.longToEntry(i, blockNumEntry);
					
					OperationStatus result =
						blockNumDB.delete(t, blockNumEntry);
					if(result.equals(OperationStatus.SUCCESS))
						deleted++;
					
					if((curBlocks-i) % 2048 == 0) {
						t.commit();
						t = null;
					}

					freeBlocks.remove(i);
					
					synchronized(this) {
						maxBlocks = maxBlocksInStore;
						curBlocks = blocksInStore;
						if(maxBlocks >= curBlocks) break;
					}
					
				}
				
				if(t != null)
					t.commit();
				
				System.err.println("Deleted "+deleted+" keys");
				
				t = null;
				
				if(offline) break;
				System.err.println("Checking...");
				synchronized(this) {
					maxBlocks = maxBlocksInStore;
					curBlocks = blocksInStore;
					if(maxBlocks >= curBlocks) break;
				}
			}
			
			storeRAF.setLength(maxBlocksInStore * (dataBlockSize + headerBlockSize));
			lruRAF.setLength(maxBlocksInStore * 8);
			if(keysRAF != null)
				keysRAF.setLength(maxBlocksInStore * keyLength);
			
			blocksInStore = maxBlocksInStore;
			System.err.println("Successfully shrunk store to "+blocksInStore);
			
		} finally {
			if(t != null) t.abort();
		}
	}

	public static final short TYPE_CHK = 0;
	public static final short TYPE_PUBKEY = 1;
	public static final short TYPE_SSK = 2;
	
	/**
	* Recreate the index from the data file. Call this when the index has been corrupted.
	 * @param reconstructFile 
	* @param the directory where the store is located
	* @throws DatabaseException If the store cannot be opened because of a database problem.
	* @throws IOException If the store cannot be opened because of a filesystem problem.
	* @throws FileNotFoundException if the dir does not exist and could not be created
	*/
	private BerkeleyDBFreenetStore(Environment env, String prefix, File storeFile, File lruFile, File keysFile, File fixSecondaryFile, long maxChkBlocks, short type, boolean noCheck, SemiOrderedShutdownHook storeShutdownHook, File reconstructFile, StoreCallback callback) throws DatabaseException, IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.storeType = type;
		this.callback = callback;
		this.keyLength = callback.fullKeyLength();
		this.dataBlockSize = callback.dataLength();
		this.headerBlockSize = callback.headerLength();
		this.collisionPossible = callback.collisionPossible();
		callback.setStore(this);
		this.freeBlocks = new SortedLongSet();
		this.maxBlocksInStore=maxChkBlocks;
		this.environment = env;
		this.reconstructFile = reconstructFile;
		name = prefix;
		
		wipeOldDatabases(environment, prefix);
		
		// Delete old database(s).
		
		// Initialize CHK database
		DatabaseConfig dbConfig = new DatabaseConfig();
		dbConfig.setAllowCreate(true);
		dbConfig.setTransactional(true);
		
		keysDB = environment.openDatabase(null,prefix+"CHK",dbConfig);
		
		if(keysDB.count() > 0)
			throw new IllegalStateException("Wiped old store but it still contains "+keysDB.count()+" keys!");
		
		this.fixSecondaryFile = fixSecondaryFile;
		fixSecondaryFile.delete();
		
		// Initialize secondary CHK database sorted on accesstime
		SecondaryConfig secDbConfig = new SecondaryConfig();
		secDbConfig.setAllowCreate(true);
		secDbConfig.setSortedDuplicates(true);
		secDbConfig.setTransactional(true);
		secDbConfig.setAllowPopulate(true);
		storeBlockTupleBinding = new StoreBlockTupleBinding();
		AccessTimeKeyCreator accessTimeKeyCreator =
			new AccessTimeKeyCreator(storeBlockTupleBinding);
		secDbConfig.setKeyCreator(accessTimeKeyCreator);
		accessTimeDB = environment.openSecondaryDatabase
							(null, prefix+"CHK_accessTime", keysDB, secDbConfig);
		
		// Initialize other secondary database sorted on block number
		SecondaryConfig blockNoDbConfig = new SecondaryConfig();
		blockNoDbConfig.setAllowCreate(true);
		blockNoDbConfig.setSortedDuplicates(false);
		blockNoDbConfig.setAllowPopulate(true);
		blockNoDbConfig.setTransactional(true);
		
		BlockNumberKeyCreator bnkc =
			new BlockNumberKeyCreator(storeBlockTupleBinding);
		blockNoDbConfig.setKeyCreator(bnkc);
		System.err.println("Creating block db index");
		blockNumDB = environment.openSecondaryDatabase
			(null, prefix+"CHK_blockNum", keysDB, blockNoDbConfig);
		
		// Initialize the store file
		if(!storeFile.exists())
			if(!storeFile.createNewFile())
				throw new DatabaseException("can't create a new file "+storeFile+" !");
		storeRAF = new RandomAccessFile(storeFile,"rw");
		
		if(!lruFile.exists())
			if(!lruFile.createNewFile())
				throw new DatabaseException("can't create a new file "+lruFile+" !");
		lruRAF = new RandomAccessFile(lruFile,"rw");
		
		if(keysFile != null) {
			if(!keysFile.exists())
				if(!keysFile.createNewFile())
					throw new DatabaseException("can't create a new file "+keysFile+" !");
			keysRAF = new RandomAccessFile(keysFile,"rw");
		} else
			keysRAF = null;
		
		blocksInStore = 0;
		
		lastRecentlyUsed = 0;
		
		reconstruct(type);
		
		blocksInStore = countCHKBlocksFromFile();
		lastRecentlyUsed = getMaxRecentlyUsed();
		
		if(!noCheck) {
			maybeOfflineShrink(true);
		}
		
		// Add shutdownhook
		storeShutdownHook.addEarlyJob(new ShutdownHook());
	}
	
	private static void wipeOldDatabases(Environment env, String prefix) {
		wipeDatabase(env, prefix+"CHK");
		wipeDatabase(env, prefix+"CHK_accessTime");
		wipeDatabase(env, prefix+"CHK_blockNum");
		System.err.println("Removed old database "+prefix);
	}

	private static void wipeDatabase(Environment env, String name) {
		WrapperManager.signalStarting(5*60*60*1000);
		Logger.normal(BerkeleyDBFreenetStore.class, "Wiping database "+name);
		try {
			env.removeDatabase(null, name);
		} catch (DatabaseNotFoundException e) {
			System.err.println("Database "+name+" does not exist deleting it");
		} catch (DatabaseException e) {
			Logger.error(BerkeleyDBFreenetStore.class, "Could not remove old database: "+name+": "+e, e);
			System.err.println("Could not remove old database: "+name+": "+e);
			e.printStackTrace();
		}
	}

	private void reconstruct(short type) throws DatabaseException, IOException {
		if(keysDB.count() != 0)
			throw new IllegalStateException("Store must be empty before reconstruction!");
		System.err.println("Reconstructing store index from store file: type="+type);
		Logger.error(this, "Reconstructing store index from store file: type="+type);
		WrapperManager.signalStarting((int)(Math.min(Integer.MAX_VALUE, 5*60*1000+(storeRAF.length()/(dataBlockSize+headerBlockSize))*100)));
		byte[] header = new byte[headerBlockSize];
		byte[] data = new byte[dataBlockSize];
		byte[] keyBuf = new byte[keyLength];
		long l = 0;
		long dupes = 0;
		long failures = 0;
		long expectedLength = storeRAF.length()/(dataBlockSize+headerBlockSize);
		try {
			storeRAF.seek(0);
			lruRAF.seek(0);
			long lruRAFLength = lruRAF.length();
			long keysRAFLength = 0;
			if(keysRAF != null) {
				keysRAF.seek(0);
				keysRAFLength = keysRAF.length();
			}
			for(l=0;true;l++) {
				long lruVal = 0;
				Transaction t = null;
				storeRAF.readFully(header);
				storeRAF.readFully(data);
				if(lruRAFLength > (l+1)*8) {
					try {
						lruVal = lruRAF.readLong();
					} catch (EOFException e) {
						System.err.println("EOF reading LRU file at "+lruRAF.getFilePointer()+" of "+lruRAF.length()+" l = "+l+" orig lru length = "+lruRAFLength);
						lruVal = 0;
						lruRAFLength = 0;
					}
				}
				if(lruVal == 0) {
					Logger.normal(this, "Block "+l+" : resetting LRU");
					lruVal = getNewRecentlyUsed();
				} else {
					Logger.normal(this, "Block "+l+" : LRU "+lruVal);
				}
				boolean readKey = false;
				if(keysRAF != null && keysRAFLength > (l+1)*keyLength) {
					try {
						keysRAF.readFully(keyBuf);
						readKey = true;
					} catch (EOFException e) {
						System.err.println("EOF reading keys file at "+keysRAF.getFilePointer()+" of "+keysRAF.length()+" l = "+l+" orig keys length = "+keysRAFLength);
						readKey = false;
					}
				}
				try {
					byte[] routingkey = null;
					try {
						StorableBlock block = callback.construct(data, header, null, readKey ? keyBuf : null);
						routingkey = block.getRoutingKey();
					} catch (KeyVerifyException e) {
						String err = "Bogus or unreconstructible key at slot "+l+" : "+e+" - lost block "+l;
						Logger.error(this, err, e);
						System.err.println(err);
						addFreeBlock(l, true, "can't reconsturct key ("+type+ ')');
						routingkey = null;
						failures++;
						continue;
					}
					t = environment.beginTransaction(null,null);
					StoreBlock storeBlock = new StoreBlock(l, lruVal);
					DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
					DatabaseEntry blockDBE = new DatabaseEntry();
					storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
					OperationStatus op = keysDB.putNoOverwrite(t,routingkeyDBE,blockDBE);
					if(op == OperationStatus.KEYEXIST) {
						addFreeBlock(l, true, "duplicate");
						dupes++;
					} else if(op != OperationStatus.SUCCESS) {
						addFreeBlock(l, true, "failure: "+op);
						failures++;
					}
					t.commit();
					if(l % 1024 == 0)
						System.out.println("Key "+l+ '/' +expectedLength+" OK ("+dupes+" dupes, "+failures+" failures)");
					t = null;
				} catch (DatabaseException e) {
					// t.abort() below may also throw.
					System.err.println("Error while reconstructing: "+e);
					e.printStackTrace();
				} finally {
					if(t != null) t.abort();
				}
			}
		} catch (EOFException e) {
			long size = l * (dataBlockSize + headerBlockSize);
			if(l < expectedLength) {
				System.err.println("Found end of store, truncating to "+l+" blocks : "+size+" ("+failures+" failures "+dupes+" dupes)");
				e.printStackTrace();
			} else {
				System.err.println("Confirmed store is "+expectedLength+" blocks long ("+failures+" failures "+dupes+" dupes)");
			}
			blocksInStore = l;
			try {
				storeRAF.setLength(size);
				lruRAF.setLength(l * 8);
				if(keysRAF != null)
					keysRAF.setLength(l * keyLength);
			} catch (IOException e1) {
				System.err.println("Failed to set size");
			}
		}
	}

	/**
	 * Retrieve a block.
	 * @param dontPromote If true, don't promote data to the top of the LRU if we fetch it.
	 * @return null if there is no such block stored, otherwise the block.
	 */
	public StorableBlock fetch(byte[] routingkey, byte[] fullKey, boolean dontPromote) throws IOException {
		synchronized(this) {
			if(closed)
				return null;
		}
		
		DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
		DatabaseEntry blockDBE = new DatabaseEntry();
		Cursor c = null;
		Transaction t = null;
		try {
			t = environment.beginTransaction(null,null);
			c = keysDB.openCursor(t,null);
			
			/**
			* We will have to write, unless both dontPromote and the key is valid.
			* The lock only applies to this record, so it's not a big problem for our use.
			* What *IS* a big problem is that if we take a LockMode.DEFAULT, and two threads
			* access the same key, they will both take the read lock, and then both try to
			* take the write lock. Neither can relinquish the read in order for the other to
			* take the write, so we're screwed.
			*/
			if(logMINOR) Logger.minor(this, "Fetching "+HexUtil.bytesToHex(routingkey)+" dontPromote="+dontPromote+" for "+callback);
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
						
			StorableBlock block = null;
			
			try {
				byte[] header = new byte[headerBlockSize];
				byte[] data = new byte[dataBlockSize];
				try {
					synchronized(storeRAF) {
						storeRAF.seek(storeBlock.offset*(long)(dataBlockSize+headerBlockSize));
						storeRAF.readFully(header);
						storeRAF.readFully(data);
					}
				} catch (EOFException e) {
					Logger.error(this, "No block");
					c.close();
					c = null;
					keysDB.delete(t, routingkeyDBE);
					t.commit();
					t = null;
					addFreeBlock(storeBlock.offset, true, "Data off end of store file");
					return null;
				}
				
				block = callback.construct(data, header, routingkey, fullKey);
				
				if(!dontPromote) {
					storeBlock.updateRecentlyUsed();
					DatabaseEntry updateDBE = new DatabaseEntry();
					storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
					c.putCurrent(updateDBE);
					c.close();
					c = null;
					t.commit();
					t = null;
					synchronized(storeRAF) {
						lruRAF.seek(storeBlock.offset * 8);
						lruRAF.writeLong(storeBlock.recentlyUsed);
					}
				} else {
					c.close();
					c = null;
					t.abort();
					t = null;
				}
				
				if(logMINOR) {
					Logger.minor(this, "Headers: " + header.length+" bytes, hash " + Fields.hashCode(header));
					Logger.minor(this, "Data: " + data.length + " bytes, hash " + Fields.hashCode(data) + " fetching " + HexUtil.bytesToHex(routingkey));
				}
				
			} catch(SSKVerifyException ex) {
				Logger.normal(this, "SSKBlock: Does not verify ("+ex+"), setting accessTime to 0 for : "+HexUtil.bytesToHex(routingkey), ex);
				keysDB.delete(t, routingkeyDBE);
				c.close();
				c = null;
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

	public void put(StorableBlock block, byte[] routingkey, byte[] fullKey, byte[] data, byte[] header, 
			boolean overwrite) throws KeyCollisionException, IOException {
		if(logMINOR)
			Logger.minor(this, "Putting "+HexUtil.bytesToHex(routingkey)+" for "+callback);
		StorableBlock oldBlock = fetch(routingkey, fullKey, false);
		if(oldBlock != null) {
			if(!collisionPossible) return;
			if(!block.equals(oldBlock)) {
				if(!overwrite)
					throw new KeyCollisionException();
				else
					overwrite(block, routingkey, fullKey, data, header);
			} // else return; // already in store
		} else {
			innerPut(block, routingkey, fullKey, data, header);
		}
	}
	
	private boolean overwrite(StorableBlock block, byte[] routingkey, byte[] fullKey, byte[] data, byte[] header) throws IOException {
		synchronized(this) {
			if(closed)
				return false;
		}
		
		DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
		DatabaseEntry blockDBE = new DatabaseEntry();
		Cursor c = null;
		Transaction t = null;
		try {
			t = environment.beginTransaction(null,null);
			c = keysDB.openCursor(t,null);

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
						
			synchronized(storeRAF) {
				storeRAF.seek(storeBlock.offset*(long)(dataBlockSize+headerBlockSize));
				storeRAF.write(header);
				storeRAF.write(data);
				if(keysRAF != null) {
					keysRAF.seek(storeBlock.offset * keyLength);
					keysRAF.write(fullKey);
				}
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
	
	private void innerPut(StorableBlock block, byte[] routingkey, byte[] fullKey, byte[] data, byte[] header) throws IOException {
		synchronized(this) {
			if(closed)
				return;
		}
			
		if(data.length!=dataBlockSize) {
			Logger.error(this, "This data is "+data.length+" bytes. Should be "+dataBlockSize);
			return;
		}
		if(header.length!=headerBlockSize) {
			Logger.error(this, "This header is "+data.length+" bytes. Should be "+headerBlockSize);
			return;
		}
		
		Transaction t = null;
		
		try {
			t = environment.beginTransaction(null,null);
			DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
			
			DatabaseEntry blockDBE = new DatabaseEntry();
			
			// Check whether it already exists
			
			if(logMINOR) Logger.minor(this, "Putting key "+block+" - checking whether it exists first");
			OperationStatus result = keysDB.get(t, routingkeyDBE, blockDBE, LockMode.RMW);
			
			if(result == OperationStatus.SUCCESS || result == OperationStatus.KEYEXIST) {
				if(logMINOR) Logger.minor(this, "Key already exists");
				// Key already exists!
				// But is it valid?
				t.abort();
				if(fetch(routingkey, fullKey, false) != null) return; // old key was valid, we are not overwriting
				// If we are here, it was corrupt, or it was just deleted, so we can replace it.
				if(logMINOR) Logger.minor(this, "Old key was invalid, adding anyway");
				innerPut(block, routingkey, fullKey, data, header);
				return;
			} else if(result == OperationStatus.KEYEMPTY) {
				Logger.error(this, "Got KEYEMPTY - record deleted? Shouldn't be possible with record locking...!");
				// Put it in anyway
			} else if(result == OperationStatus.NOTFOUND) {
				// Good
			} else
				throw new IllegalStateException("Unknown operation status: "+result);
			
			writeBlock(header, data, t, routingkeyDBE, fullKey);
			
			t.commit();
			t = null;
			
			if(logMINOR) {
				Logger.minor(this, "Headers: "+header.length+" bytes, hash "+Fields.hashCode(header));
				Logger.minor(this, "Data: "+data.length+" bytes, hash "+Fields.hashCode(data)+" putting "+HexUtil.bytesToHex(routingkey));
			}
				
		} catch(Throwable ex) {  // FIXME: ugly
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
	
	private void overwriteLRUBlock(byte[] header, byte[] data, Transaction t, DatabaseEntry routingkeyDBE, byte[] fullKey) throws DatabaseException, IOException {
		// Overwrite an other block
		Cursor c = accessTimeDB.openCursor(t,null);
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
		keysDB.put(t,routingkeyDBE,blockDBE);
		synchronized(storeRAF) {
			storeRAF.seek(storeBlock.getOffset()*(long)(dataBlockSize+headerBlockSize));
			storeRAF.write(header);
			storeRAF.write(data);
			lruRAF.seek(storeBlock.getOffset() * 8);
			lruRAF.writeLong(storeBlock.recentlyUsed);
			if(keysRAF != null) {
				keysRAF.seek(storeBlock.getOffset() * keyLength);
				keysRAF.write(fullKey);
			}
			writes++;
		}
	}

	private boolean writeNewBlock(long blockNum, byte[] header, byte[] data, Transaction t, DatabaseEntry routingkeyDBE, byte[] fullKey) throws DatabaseException, IOException {
		long byteOffset = blockNum*(dataBlockSize+headerBlockSize);
		StoreBlock storeBlock = new StoreBlock(this, blockNum);
		long lruValue = storeBlock.recentlyUsed;
		DatabaseEntry blockDBE = new DatabaseEntry();
		storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
		try {
			keysDB.put(t,routingkeyDBE,blockDBE);
		} catch (DatabaseException e) {
			DatabaseEntry blockNumEntry = new DatabaseEntry();
			DatabaseEntry found = new DatabaseEntry();
			LongBinding.longToEntry(blockNum, blockNumEntry);
			
			OperationStatus success =
				blockNumDB.get(t, blockNumEntry, found, LockMode.DEFAULT);

			if(success == OperationStatus.KEYEXIST || success == OperationStatus.SUCCESS) {
				System.err.println("Trying to overwrite block "+blockNum+" but already used: "+getName()+" for "+e);
				e.printStackTrace();
				Logger.error(this, "Trying to overwrite block "+blockNum+" but already used: "+getName()+" for "+e);
				return false;
			} else {
				Logger.minor(this, "Key doesn't exist for block num "+blockNum+" but caught "+e, e);
				throw e;
			}
		}
		synchronized(storeRAF) {
			try {
				storeRAF.seek(byteOffset);
			} catch (IOException ioe) {
				if(byteOffset > (2l*1024*1024*1024)) {
					Logger.error(this, "Environment does not support files bigger than 2 GB?");
					System.out.println("Environment does not support files bigger than 2 GB? (exception to follow)");
				}
				Logger.error(this, "Caught IOException on storeRAF.seek("+byteOffset+ ')');
				throw ioe;
			}
			storeRAF.write(header);
			storeRAF.write(data);
			lruRAF.seek(blockNum * 8);
			lruRAF.writeLong(lruValue);
			if(keysRAF != null) {
				keysRAF.seek(blockNum * keyLength);
				keysRAF.write(fullKey);
				if(logDEBUG)
					Logger.debug(this, "Written full key length "+fullKey.length+" to block "+blockNum+" at "+(blockNum * keyLength));
			} else if(logDEBUG && storeType == TYPE_SSK) {
				Logger.debug(this, "Not writing full key length "+fullKey.length+" for block "+blockNum);
			}
			writes++;
		}
		return true;
	}

	public final String getName() {
		return name;
	}

	private void checkSecondaryDatabaseError(Throwable ex) {
		String msg = ex.getMessage();
		if(ex instanceof DatabaseException) {
			if(msg != null && ((msg.indexOf("missing key in the primary database") > -1) ||
					msg.indexOf("the primary record contains a key that is not present in the secondary") > -1)) {
				try {
					fixSecondaryFile.createNewFile();
				} catch (IOException e) {
					Logger.error(this, "Corrupt secondary database ("+getName()+") but could not create flag file "+fixSecondaryFile);
					System.err.println("Corrupt secondary database ("+getName()+") but could not create flag file "+fixSecondaryFile);
					return; // Not sure what else we can do
				}
				Logger.error(this, "Corrupt secondary database ("+getName()+"). Should be cleaned up on restart.");
				System.err.println("Corrupt secondary database ("+getName()+"). Should be cleaned up on restart.");
				WrapperManager.restart();
				System.exit(freenet.node.NodeInitException.EXIT_DATABASE_REQUIRES_RESTART);
			} else if(ex instanceof DbChecksumException || ex instanceof RunRecoveryException || ex instanceof LogFileNotFoundException ||
					// UGH! We really shouldn't have to do this ... :(
					(msg != null && 
							(msg.indexOf("LogFileNotFoundException") >= 0 || msg.indexOf("DbChecksumException") >= 0)
							|| msg.indexOf("RunRecoveryException") >= 0)) {
				System.err.println("Corrupt database! Will be reconstructed on restart");
				Logger.error(this, "Corrupt database! Will be reconstructed on restart");
				try {
					reconstructFile.createNewFile();
				} catch (IOException e) {
					Logger.error(this, "Corrupt database ("+getName()+") but could not create flag file "+reconstructFile);
					System.err.println("Corrupt database ("+getName()+") but could not create flag file "+reconstructFile);
					return; // Not sure what else we can do
				}
				System.err.println("Restarting to fix corrupt store database...");
				Logger.error(this, "Restarting to fix corrupt store database...");
				WrapperManager.restart();
			} else {
				if(ex.getCause() != null)
					checkSecondaryDatabaseError(ex.getCause());
			}
		}
	}

	private void writeBlock(byte[] header, byte[] data, Transaction t, DatabaseEntry routingkeyDBE, byte[] fullKey) throws DatabaseException, IOException {
		
		long blockNum;
		
		// Keep trying until we succeed.
		while(true) {
				if((blockNum = grabFreeBlock()) >= 0) {
					if(logMINOR)
						Logger.minor(this, "Overwriting free block: "+blockNum);
					if(writeNewBlock(blockNum, header, data, t, routingkeyDBE, fullKey))
						return;
				} else if(blocksInStore<maxBlocksInStore) {
					// Expand the store file
					synchronized(blocksInStoreLock) {
						blockNum = blocksInStore;
						blocksInStore++;
					}
					if(logMINOR)
						Logger.minor(this, "Expanding store and writing block "+blockNum);
					// Just in case
					freeBlocks.remove(blockNum);
					if(writeNewBlock(blockNum, header, data, t, routingkeyDBE, fullKey))
						return;
				} else {
					if(logMINOR)
						Logger.minor(this, "Overwriting LRU block");
					overwriteLRUBlock(header, data, t, routingkeyDBE, fullKey);
					return;
				}
			
		}
		
	}

	private long grabFreeBlock() {
		while(!freeBlocks.isEmpty()) {
			long blockNum = freeBlocks.removeFirst();
			if(blockNum < maxBlocksInStore) return blockNum;
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
			long offset = ti.readLong();
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
			LongBinding.longToEntry(storeblock.getRecentlyUsed(), resultEntry);
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
			LongBinding.longToEntry(storeblock.offset, resultEntry);
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
		try {
			// FIXME: 	we should be sure all access to the database has stopped
			//			before we try to close it. Currently we just guess
			//			This is nothing too problematic however since the worst thing that should
			//			happen is that we miss the last few store()'s and get an exception.
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Closing database "+this);
			closed = true;
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
					if(storeRAF != null)
						storeRAF.close();
					if(lruRAF != null)
						lruRAF.close();
					if(keysRAF != null)
						keysRAF.close();
				} catch (Throwable t) {
					if(!(t instanceof RunRecoveryException || t instanceof OutOfMemoryError)) {
						System.err.println("Caught closing database: "+t);
						t.printStackTrace();
					}
				}
				try {
					if(accessTimeDB != null)
						accessTimeDB.close();
				} catch (Throwable t) {
					if(!(t instanceof RunRecoveryException || t instanceof OutOfMemoryError)) {
						System.err.println("Caught closing database: "+t);
						t.printStackTrace();
					}
				}
				try {
					if(blockNumDB != null)
						blockNumDB.close();
				} catch (Throwable t) {
					if(!(t instanceof RunRecoveryException || t instanceof OutOfMemoryError)) {
						System.err.println("Caught closing database: "+t);
						t.printStackTrace();
					}
				}
				try {	
					if(keysDB != null)
						keysDB.close();
				} catch (Throwable t) {
					if(!(t instanceof RunRecoveryException || t instanceof OutOfMemoryError)) {
						System.err.println("Caught closing database: "+t);
						t.printStackTrace();
					}
				}
				if(logMINOR) Logger.minor(this, "Closing database finished.");
				System.err.println("Closed database");
				reallyClosed = true;
			}
		} catch(Throwable ex) {
			try {
				Logger.error(this,"Error while closing database.",ex);
				ex.printStackTrace();
			} catch (Throwable t) {
				// Return anyway
			}
		}
	}
	
	private long highestBlockNumberInDatabase() throws DatabaseException {
		Cursor c = null;
		try {
			c = blockNumDB.openCursor(null,null);
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
		long fileSize = storeRAF.length();
		return fileSize / keySize;
	}

	private long getMaxRecentlyUsed() {
		long maxRecentlyUsed = 0;
		
		Cursor c = null;
		try {
			c = accessTimeDB.openCursor(null,null);
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

	public void setMaxKeys(long maxStoreKeys, boolean forceBigShrink) throws DatabaseException, IOException {
		synchronized(this) {
			maxBlocksInStore = maxStoreKeys;
		}
		maybeOnlineShrink(false);
	}
	
	public long getMaxKeys() {
		return maxBlocksInStore;
	}

	public long hits() {
		return hits;
	}
	
	public long misses() {
		return misses;
	}

	public long writes() {
		return writes;
	}
	
	public long keyCount() {
		return blocksInStore;
	}
}
