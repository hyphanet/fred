package freenet.store;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.RunRecoveryException;
import com.sleepycat.je.SecondaryConfig;
import com.sleepycat.je.SecondaryDatabase;
import com.sleepycat.je.SecondaryKeyCreator;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.log.DbChecksumException;
import com.sleepycat.je.log.LogFileNotFoundException;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.RandomSource;
import freenet.keys.KeyVerifyException;
import freenet.keys.SSKBlock;
import freenet.node.SemiOrderedShutdownHook;
import freenet.node.stats.StoreAccessStats;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.OOMHook;
import freenet.support.SortedLongSet;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * Freenet datastore based on BerkelyDB Java Edition by Sleepycat Software, Inc.
 * (now Oracle). More info at <a
 * href="http://www.oracle.com/database/berkeley-db/je/">http://www.oracle.com/database/berkeley-db/je/</a>.
 * 
 * @author tubbie
 * @author amphibian
 */
public class BerkeleyDBFreenetStore<T extends StorableBlock> implements FreenetStore<T>, OOMHook {

	private static boolean logMINOR;
	private static boolean logDEBUG;
	
	// If we get a DbChecksumException, create this file.
	private final File reconstructFile;
	private final int dataBlockSize; 
	private final int headerBlockSize;
	private final RandomSource random;

	private final Environment environment;
	private final TupleBinding<StoreBlock> storeBlockTupleBinding;
	private final File fixSecondaryFile;
	
	private long blocksInStore = 0;
	private final Object blocksInStoreLock = new Object();
	private long maxBlocksInStore;
	private long hits = 0;
	private long misses = 0;
	private long writes = 0;
	private final int keyLength;
	private Database keysDB;
	private SecondaryDatabase accessTimeDB;
	private SecondaryDatabase blockNumDB;
	private RandomAccessFile storeRAF;
	private RandomAccessFile keysRAF;
	private RandomAccessFile lruRAF;
	private FileChannel storeFC;
	private FileChannel keysFC;
	private FileChannel lruFC;
	private final SortedLongSet freeBlocks;
	private final String name;
	/** Callback which translates records to blocks and back, specifies the size of blocks etc. */
	private final StoreCallback<T> callback;
	private final boolean collisionPossible;
	
	private long lastRecentlyUsed;
	private final Object lastRecentlyUsedSync = new Object();
	
	private boolean closed;
	private boolean reallyClosed;
	
	public static String getName(boolean isStore, StoreType type) {
		String newDBPrefix = typeName(type)+ '-' +(isStore ? "store" : "cache")+ '-';
		return newDBPrefix + "CHK";
	}
	
	public static File getFile(boolean isStore, StoreType type, File baseStoreDir, String suffix) {
		String newStoreFileName = typeName(type) + suffix + '.' + (isStore ? "store" : "cache");
		return new File(baseStoreDir, newStoreFileName);
	}
	
	public static <T extends StorableBlock> FreenetStore<T> construct(File baseStoreDir, boolean isStore, String suffix, long maxStoreKeys,
	        StoreType type, Environment storeEnvironment, SemiOrderedShutdownHook storeShutdownHook,
	        File reconstructFile, StoreCallback<T> callback, RandomSource random) throws DatabaseException, IOException {
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
		
		System.err.println("Opening database using "+newStoreFile);
		return openStore(storeEnvironment, newDBPrefix, newStoreFile, lruFile, keysFile, newFixSecondaryFile, maxStoreKeys, storeShutdownHook,
				reconstructFile, callback, random);
	}

	private static <T extends StorableBlock> FreenetStore<T> openStore(Environment storeEnvironment, String newDBPrefix, File newStoreFile, File lruFile,
			File keysFile, File newFixSecondaryFile, long maxStoreKeys, SemiOrderedShutdownHook storeShutdownHook, 
			File reconstructFile, StoreCallback<T> callback, RandomSource random) throws DatabaseException, IOException {
		try {
			// First try just opening it.
			return new BerkeleyDBFreenetStore<T>(storeEnvironment, newDBPrefix, newStoreFile, lruFile, keysFile, newFixSecondaryFile,
					maxStoreKeys, false, storeShutdownHook, reconstructFile, 
					callback, random);
		} catch (DatabaseException e) {
			
			// Try a reconstruct
			
			System.err.println("Could not open store: "+e);
			e.printStackTrace();
			
			System.err.println("Attempting to reconstruct index...");
			WrapperManager.signalStarting(5*60*60*1000);
			
			// Reconstruct
			
			return new BerkeleyDBFreenetStore<T>(storeEnvironment, newDBPrefix, newStoreFile, lruFile, keysFile, newFixSecondaryFile, 
					maxStoreKeys, storeShutdownHook, reconstructFile, callback, random);
		}
	}

	private static String typeName(StoreType type) {
		return type.toString().toLowerCase();
	}
	
	/**
	 * Initializes the datastore
	 * 
	 * @param env
	 *            Berkeley DB {@link Environment}.
	 * @param prefix
	 *            Database name prefix
	 * @param storeFile
	 *            Store file, where the actual data are stored
	 * @param lruFile
	 *            LRU data file, flat file store for recovery
	 * @param keysFile
	 *            Keys data file, flat file store for recovery, created only if
	 *            <code>callback.storeFullKeys()</code> is <code>true</code>
	 * @param fixSecondaryFile
	 *            Flag file. Created when secondary database error occur. If
	 *            this file exist on start, delete it and recreate the secondary
	 *            database.
	 * @param maxChkBlocks
	 *            maximum number of blocks
	 * @param wipe
	 *            If <code>true</code>, wipe and reconstruct the database.
	 * @param storeShutdownHook
	 *            {@link SemiOrderedShutdownHook} for hooking database shutdown
	 *            hook.
	 * @param reconstructFile
	 *            Flag file. Created when database crash.
	 * @param callback
	 *            {@link StoreCallback} object for this store.
	 * @throws IOException
	 * @throws DatabaseException
	 */
	private BerkeleyDBFreenetStore(Environment env, String prefix, File storeFile, File lruFile, File keysFile,
			File fixSecondaryFile, long maxChkBlocks, boolean wipe, SemiOrderedShutdownHook storeShutdownHook,
			File reconstructFile,
			StoreCallback<T> callback, RandomSource random) throws IOException, DatabaseException {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
		
		this.random = random;
		this.environment = env;
		this.name = prefix;
		this.fixSecondaryFile = fixSecondaryFile;
		this.maxBlocksInStore = maxChkBlocks;
		this.reconstructFile = reconstructFile;
		this.callback = callback;
		this.collisionPossible = callback.collisionPossible();
		this.dataBlockSize = callback.dataLength();
		this.headerBlockSize = callback.headerLength();
		this.keyLength = callback.fullKeyLength();
		callback.setStore(this);
		
		OOMHandler.addOOMHook(this);

		this.freeBlocks = new SortedLongSet();

		// Delete old database(s).
		if (wipe) {
			System.err.println("Wiping old database for " + prefix);
			wipeOldDatabases(environment, prefix);
		}

		// Initialize CHK database
		DatabaseConfig dbConfig = new DatabaseConfig();
		dbConfig.setAllowCreate(true);
		dbConfig.setTransactional(true);

		keysDB = environment.openDatabase(null,prefix+"CHK",dbConfig);
		System.err.println("Opened main database for "+prefix);

		if(fixSecondaryFile.exists()) {
			fixSecondaryFile.delete();
			removeSecondaryDatabase();
		}

		storeBlockTupleBinding = new StoreBlockTupleBinding();

		// Initialize secondary CHK database sorted on accesstime
		accessTimeDB = openSecondaryDataBase(prefix + "CHK_accessTime", keysDB.count() == 0 || wipe, wipe, true, new AccessTimeKeyCreator(
				storeBlockTupleBinding));

		// Initialize other secondary database sorted on block number
		blockNumDB = openSecondaryDataBase(prefix + "CHK_blockNum", keysDB.count() == 0 || wipe, wipe, false, new BlockNumberKeyCreator(
				storeBlockTupleBinding));

		// Initialize the store file
		try {
			if(!storeFile.exists())
				if(!storeFile.createNewFile())
					throw new IOException("Can't create a new file " + storeFile + " !");
			storeRAF = new RandomAccessFile(storeFile,"rw");
			storeFC = storeRAF.getChannel();

			if(!lruFile.exists()) 
				if(!lruFile.createNewFile())
					throw new IOException("Can't create a new file " + lruFile + " !");
			lruRAF = new RandomAccessFile(lruFile,"rw");
			lruFC = lruRAF.getChannel();

			if(keysFile != null) {
				if(!keysFile.exists())
					if(!keysFile.createNewFile())
						throw new IOException("Can't create a new file " + keysFile + " !");
				keysRAF = new RandomAccessFile(keysFile,"rw");
				keysFC = keysRAF.getChannel();
			} else keysRAF = null;

			if (wipe) {
                                // wipe and reconstruct
				blocksInStore = 0;
				lastRecentlyUsed = 0;

				// REDFLAG: BDBFS cannot be used for a client-cache, so always pass in false.
				reconstruct(false);

				blocksInStore = countCHKBlocksFromFile();
				lastRecentlyUsed = getMaxRecentlyUsed();

				maybeOfflineShrink(true);
			} else {
                                // just open
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

				maybeOfflineShrink(dontCheckForHolesShrinking);
				chkBlocksFromFile = countCHKBlocksFromFile();
				blocksInStore = Math.max(blocksInStore, chkBlocksFromFile);
				}

			// Add shutdownhook
			storeShutdownHook.addEarlyJob(new ShutdownHook());
		} catch (DatabaseException t) {
			Logger.error(this, "Caught exception, closing database: " + prefix, t);
			System.err.println("Caught exception, closing database: " + prefix + " (" + t + ")");
			t.printStackTrace();
			close(false);
			
			throw t;
		} catch (IOException t) {
			System.err.println("Caught exception, closing database: " + prefix + " (" + t + ")");
			Logger.error(this, "Caught exception, closing database: " + prefix, t);
			close(false);
			
			throw t;
		}
	}

	private long checkForHoles(long blocksInFile, boolean dontTruncate) throws DatabaseException {
		System.err.println("Checking for holes in database... "+blocksInFile+" blocks in file");
		WrapperManager.signalStarting((int) Math.min(Integer.MAX_VALUE, 5 * 60 * 1000 + blocksInFile * 100L)); // 10/sec
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

	private final Object shrinkLock = new Object();
	private boolean shrinking = false;
	
	/**
	 * Do an offline shrink, if necessary. Will not return until completed.
	 * 
	 * @param dontCheckForHoles If <code>true</code>, don't check for holes.
	 * @throws DatabaseException
	 * @throws IOException
	 */
	private void maybeOfflineShrink(boolean dontCheckForHoles) throws DatabaseException, IOException {
		if(blocksInStore <= maxBlocksInStore) return;
		maybeSlowShrink(dontCheckForHoles, true);
	}

	/**
	 * Do an online shrink, if necessary. This method is non-blocking (i.e. it
	 * will do the shrink on a new thread).
	 * 
	 * @param forceBigOnlineShrinks If true, force the node to shrink the store
	 *        immediately even if it is a major (more than <tt>10%</tt>)
	 *        shrink. <br />
	 *        Normally this flag is should not be set because online shrinks
	 *        drops the most recently used data; the best thing to do is to
	 *        restart the node and let it do an offline shrink.
	 * @throws DatabaseException If a database error occurs.
	 * @throws IOException If an I/O error occurs.
	 * @return <code>true</code> if the database will be shrunk in the
	 *         background (or the database is already small enough),
	 *         <code>false</code>otherwise (for example: major shrink is
	 *         needed but <code>forceBigOnlineShrinks</code> was not set).
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
						synchronized(shrinkLock) { if(shrinking) return; shrinking = true; }
						maybeQuickShrink(false);
					} catch (Throwable t) {
						Logger.error(this, "Online shrink failed: "+t, t);
					} finally {
						synchronized(shrinkLock) { shrinking = false; }
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
		List<Integer> wantedKeep = new ArrayList<Integer>(); // keep; content is wanted, and is in the right place
		List<Integer> unwantedIgnore = new ArrayList<Integer>(); // ignore; content is not wanted, and is not in the right place
		List<Integer> wantedMove = new ArrayList<Integer>(); // content is wanted, but is in the wrong part of the store
		List<Integer> unwantedMove = new ArrayList<Integer>(); // content is not wanted, but is in the part of the store we will keep
		List<Integer> alreadyDropped = new ArrayList<Integer>(); // any blocks past the end which have already been truncated, but which there are still database blocks pointing to
		
		Cursor c = null;
		Transaction t = null;

		long newSize = maxBlocksInStore;
		if(blocksInStore < maxBlocksInStore) return;
		
		System.err.println("Shrinking from "+blocksInStore+" to "+maxBlocksInStore+" (from db "+keysDB.count()+" from file "+countCHKBlocksFromFile()+ ')');
		
		if(!dontCheckForHoles)
			checkForHoles(maxBlocksInStore, true);
		
		WrapperManager.signalStarting((int) (Math.min(Integer.MAX_VALUE, 5 * 60 * 1000 + blocksInStore * 100L))); // 10 per second
		
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
				StoreBlock storeBlock = storeBlockTupleBinding.entryToObject(blockDBE);
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
				Integer blockNum = (int) storeBlock.offset;
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
						System.out.println("Reading store prior to shrink: "+(x*100L/realSize)+ "% ( "+x+ '/' +realSize+ ')');
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
		
		Integer[] wantedKeepNums = wantedKeep.toArray(new Integer[wantedKeep.size()]);
		Integer[] unwantedIgnoreNums = unwantedIgnore.toArray(new Integer[unwantedIgnore.size()]);
		Integer[] wantedMoveNums = wantedMove.toArray(new Integer[wantedMove.size()]);
		Integer[] unwantedMoveNums = unwantedMove.toArray(new Integer[unwantedMove.size()]);
		long[] freeEarlySlots = freeBlocks.toArray();
		Arrays.sort(wantedKeepNums);
		Arrays.sort(unwantedIgnoreNums);
		Arrays.sort(wantedMoveNums);
		Arrays.sort(unwantedMoveNums);
		
		for(int i=0;i<newSize;i++) {
			Integer ii = Integer.valueOf(i);
			if(Arrays.binarySearch(wantedKeepNums, ii) >= 0) continue;
			if(Arrays.binarySearch(unwantedIgnoreNums, ii) >= 0) continue;
			if(Arrays.binarySearch(wantedMoveNums, ii) >= 0) continue;
			if(Arrays.binarySearch(unwantedMoveNums, ii) >= 0) continue;
			unwantedMove.add(ii);
		}
		unwantedMoveNums = unwantedMove.toArray(new Integer[unwantedMove.size()]);
		
		System.err.println("Keys to keep where they are:     "+wantedKeepNums.length);
		System.err.println("Keys which will be wiped anyway: "+unwantedIgnoreNums.length);
		System.err.println("Keys to move:                    "+wantedMoveNums.length);
		System.err.println("Keys to be moved over:           "+unwantedMoveNums.length);
		System.err.println("Free slots to be moved over:     "+freeEarlySlots.length);
		
		// Now move all the wantedMove blocks onto the corresponding unwantedMove's.
		
		WrapperManager.signalStarting((int)Math.min(Integer.MAX_VALUE, (5*60*1000 + wantedMoveNums.length*1000L + alreadyDropped.size() * 100L))); // 1 per second
		
		ByteBuffer buf = ByteBuffer.allocate(headerBlockSize + dataBlockSize);
		long lruValue;
		byte[] keyBuf = new byte[keyLength];
		t = null;
		try {
			t = environment.beginTransaction(null,null);
			if(alreadyDropped.size() > 0) {
				System.err.println("Deleting "+alreadyDropped.size()+" blocks beyond the length of the file");
				for(int i=0;i<alreadyDropped.size();i++) {
					int unwantedBlock = (alreadyDropped.get(i)).intValue();
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
					unwantedBlock = Integer.valueOf((int) freeEarlySlots[i]); // will fit in an int
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
					buf.rewind();
					do {
						int byteRead = storeFC.read(buf, entry * (headerBlockSize + dataBlockSize) + buf.position());
						if (byteRead == -1)
							throw new EOFException();
					} while (buf.hasRemaining());
					buf.flip();
					lruValue = 0;
					if(lruRAF.length() > ((entry + 1) * 8)) {
						readLRU = true;
						lruValue = fcReadLRU(entry);
					}
					if(keysRAF != null && keysRAF.length() > ((entry + 1) * keyLength)) {
						readKey = true;
						fcReadKey(entry, keyBuf);
					}
				} catch (EOFException e) {
					System.err.println("Was reading "+wantedBlock+" to write to "+unwantedBlock);
					System.err.println(e);
					e.printStackTrace();
					throw e;
				}
				entry = unwantedBlock.longValue();
				do {
					int byteWritten = storeFC.write(buf, entry * (headerBlockSize + dataBlockSize) + buf.position());
					if (byteWritten == -1)
						throw new EOFException();
				} while (buf.hasRemaining());
				if(readLRU) {
					fcWriteLRU(entry, lruValue);
				}
				if(readKey) {
					fcWriteKey(entry, keyBuf);
				}
				
				// Update the database w.r.t. the old block.
				
				DatabaseEntry routingKeyDBE = new DatabaseEntry();
				DatabaseEntry blockDBE = new DatabaseEntry();
				blockNumDB.get(t, wantedBlockEntry, routingKeyDBE, blockDBE, LockMode.RMW);
				StoreBlock block = storeBlockTupleBinding.entryToObject(blockDBE);
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
		WrapperManager.signalStarting((int) Math.min(Integer.MAX_VALUE, 5*60*1000 + (totalUnwantedBlocks-wantedMoveNums.length) * 100L));
		// If there are any slots left over, they must be free.
		
		// FIXME put these into the database as we do in reconstruct().
		// Not doing that now as its not immediately obvious how to deal with it...
		
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
			WrapperManager.signalStarting((int)Math.min(Integer.MAX_VALUE, (5*60*1000 + 100L * (Math.max(0, curBlocks-maxBlocks)))));
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
					
					t.commit();
					t = null;

					freeBlocks.remove(i);

					long chkBlocksInDatabase = highestBlockNumberInDatabase();
					synchronized(this) {
						maxBlocks = maxBlocksInStore;
						curBlocks = blocksInStore = chkBlocksInDatabase;
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

	/**
	 * Recreate the index from the data file. Call this when the index has been corrupted.
	 *
	 * @param env
	 *            Berkeley DB {@link Environment}.
	 * @param prefix
	 *            Datastore name prefix
	 * @param storeFile
	 *            Store file, where the actual data are stored
	 * @param lruFile
	 *            LRU data file, flat file store for recovery
	 * @param keysFile
	 *            Keys data file, flat file store for recvoery
	 * @param fixSecondaryFile
	 *            Flag file. Created when secondary database error occur. If
	 *            this file exist on start, delete it and recreate the secondary
	 *            database.
	 * @param maxChkBlocks
	 *            maximum number of blocks
	 * @param storeShutdownHook
	 *            {@link SemiOrderedShutdownHook} for hooking database shutdown
	 *            hook.
	 * @param reconstructFile
	 *            Flag file. Created when database crash.
	 * @param callback
	 *            {@link StoreCallback} object for this store.
	 * @throws IOException
	 * @throws DatabaseException
	 */
	private BerkeleyDBFreenetStore(Environment env, String prefix, File storeFile, File lruFile, File keysFile,
			File fixSecondaryFile, long maxChkBlocks, SemiOrderedShutdownHook storeShutdownHook, File reconstructFile,
			StoreCallback<T> callback, RandomSource random) throws DatabaseException, IOException {
		this(env, prefix, storeFile, lruFile, keysFile, fixSecondaryFile, maxChkBlocks, true, storeShutdownHook,
				reconstructFile, callback, random);
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

	/**
	 * Reconstruct the database using flat file stores and other dark magic.
	 * 
	 * <strong>You are not expected to understand this.</strong>
	 * 
	 * <dl>
	 * <dt>header + data</dt>
	 * <dd>read from storeRAF, always available.</dd>
	 * 
	 * <dt>fullKey</dt>
	 * <dd>read from keyRAF, maybe null.</dd>
	 * 
	 * <dt>routingkey </dt>
	 * <dd>
	 * <ol>
	 * <li><code>callback.routingKeyFromFullKey(); </code></li>
	 * <li>if <code>null</code> or <code>KeyVerifyException</code>,<code> callback.construct().getRoutingKey()</code>,
	 * may throw <code>KeyVerifyException</code></li>
	 * </ol>
	 * <code>fullKey</code> (and hence <code>callback.routingKeyFromFullKey(); </code>) may be
	 * phantom, hence we must verify
	 * <code> callback.construct().getRoutingKey()  == routingkey </code> on <code>fetch()</code>
	 * </dd>
	 * </dl>
	 * 
	 * On <code>OperationStatus.KEYEXIST</code> or bad <code> callback.construct</code>:
	 * <ol>
	 * <li>insert a database entry with random key, (minimum lru - 1); </li>
	 * <li>if <code>op != OperationStatus.SUCCESS</code>, <code>addFreeBlock()</code>.</li>
	 * </ol>
	 * 
	 * 
	 * @throws DatabaseException
	 * @throws IOException
	 */
	private void reconstruct(boolean canReadClientCache) throws DatabaseException, IOException {
		if(keysDB.count() != 0)
			throw new IllegalStateException("Store must be empty before reconstruction!");
		// Timeout must be well below Integer.MAX_VALUE. It is added to previous timeouts in an integer value.
		// If it's too high, we get wraparound and instant timeout.
		int timeout = (int) (Math.min(7 * 24 * 60 * 60 * 1000, 5 * 60 * 1000
		        + (storeRAF.length() / (dataBlockSize + headerBlockSize)) * 1000L));
		System.err.println("Reconstructing store index from store file: callback="+callback+" - allowing "+timeout+"ms");
		Logger.error(this, "Reconstructing store index from store file: callback="+callback);
		WrapperManager.signalStarting(timeout);
		// Reusing the buffer is safe, provided we don't do anything with the resulting StoreBlock.
		byte[] header = new byte[headerBlockSize];
		byte[] data = new byte[dataBlockSize];
		byte[] keyBuf = new byte[keyLength];
		long l = 0;
		long dupes = 0;
		long failures = 0;
		long expectedLength = storeRAF.length()/(dataBlockSize+headerBlockSize);
		// Find minimum and maximum LRU.
		long minLRU = Long.MAX_VALUE;
		long maxLRU = Long.MIN_VALUE;
		try {
			lruRAF.seek(0);
			for(long i=0;i<lruRAF.length()/8;i++) {
				long lru = lruRAF.readLong();
				if(lru > maxLRU) maxLRU = lru;
				if(lru < minLRU) minLRU = lru;
			}
		} catch (IOException e) {
			// We don't want this to be fatal...
		}
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
				if(l % 1024 == 0)
					System.out.println("Key "+l+ '/' +expectedLength+" OK ("+dupes+" dupes, "+failures+" failures)");

				long lruVal = 0;
				Transaction t = null;
				boolean dataRead = false;
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
					Logger.minor(this, "Block " + l + " : resetting LRU");
					lruVal = getNewRecentlyUsed();
				} else {
					Logger.minor(this, "Block " + l + " : LRU " + lruVal);
				}
				boolean readKey = false;
				if(keysRAF != null && keyBuf != null && keysRAFLength > (l+1)*keyLength) {
					try {
						keysRAF.readFully(keyBuf);
						readKey = true;
					} catch (EOFException e) {
						System.err.println("EOF reading keys file at "+keysRAF.getFilePointer()+" of "+keysRAF.length()+" l = "+l+" orig keys length = "+keysRAFLength);
						readKey = false;
					}
				}
				if(!readKey) keyBuf = null;
				boolean keyFromData = false;
				try {
					byte[] routingkey = null;
					if(keyBuf != null && !isAllNull(keyBuf)) {
						routingkey = callback.routingKeyFromFullKey(keyBuf);
						if(routingkey == keyBuf) {
							// Copy it.
							byte[] newkey = new byte[routingkey.length];
							System.arraycopy(routingkey, 0, newkey, 0, routingkey.length);
							routingkey = newkey;
						}
					}
					if (!dataRead) {
						storeRAF.seek(l * (headerBlockSize + dataBlockSize));
						storeRAF.readFully(header);
						storeRAF.readFully(data);
						dataRead = true;
					}
					if (routingkey == null && !isAllNull(header) && !isAllNull(data)) {
						keyFromData = true;
						try {
							StorableBlock block = callback.construct(data, header, null, keyBuf, false, false, null, null);
							routingkey = block.getRoutingKey();
						} catch (KeyVerifyException e) {
							String err = "Bogus or unreconstructible key at slot "+l+" : "+e+" - lost block "+l;
							Logger.error(this, err, e);
							System.err.println(err);
							failures++;
						}
					}
					
					if (routingkey == null) { // can't recover, mark this as free
						t = environment.beginTransaction(null, null);
						reconstructAddFreeBlock(l, t, --minLRU);
						t.commitNoSync();
						t = null;
						continue;
					}
					
					t = environment.beginTransaction(null,null);
					StoreBlock storeBlock = new StoreBlock(l, lruVal);
					DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
					DatabaseEntry blockDBE = new DatabaseEntry();
					storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
					OperationStatus op = keysDB.putNoOverwrite(t,routingkeyDBE,blockDBE);
					if(op == OperationStatus.KEYEXIST) {
						if(!keyFromData) {
							byte[] oldRoutingkey = routingkey;
							try {
								if (!dataRead) {
									storeRAF.seek(l * (headerBlockSize + dataBlockSize));
									storeRAF.readFully(header);
									storeRAF.readFully(data);
									dataRead = true;
								}
								StorableBlock block = callback.construct(data, header, null, keyBuf, false, false, null, null);
								routingkey = block.getRoutingKey();
								if(Arrays.equals(oldRoutingkey, routingkey)) {
									dupes++;
									String err = "Really duplicated block: "+l+" key null = "+isAllNull(keyBuf)+" routing key null = "+isAllNull(routingkey)+" headers null = "+isAllNull(header)+" data null = "+isAllNull(data);
									Logger.error(this, err);
									System.err.println(err);
									reconstructAddFreeBlock(l, t, --minLRU);
								} else {
									routingkeyDBE = new DatabaseEntry(routingkey);
									op = keysDB.putNoOverwrite(t,routingkeyDBE,blockDBE);
									if(op == OperationStatus.KEYEXIST) {
										dupes++;
										String err = "Duplicate block, reconstructed the key, different duplicate block!: "+l+" key null = "+isAllNull(keyBuf)+" routing key null = "+isAllNull(routingkey)+" headers null = "+isAllNull(header)+" data null = "+isAllNull(data);
										Logger.error(this, err);
										System.err.println(err);
										reconstructAddFreeBlock(l, t, --minLRU);
									} else if(op != OperationStatus.SUCCESS) {
										failures++;
										String err = "Unknown error: "+op+" for duplicate block "+l+" after reconstructing key";
										Logger.error(this, err);
										System.err.println(err);
										reconstructAddFreeBlock(l, t, --minLRU);
									} // Else it worked.
								}
							} catch (KeyVerifyException e) {
								String err = "Duplicate slot, bogus or unreconstructible key at "+l+" : "+e+" - lost block "+l;
								Logger.error(this, err, e);
								System.err.println(err);
								failures++;
								reconstructAddFreeBlock(l, t, --minLRU);
							}
						} else {
							Logger.error(this, "Duplicate block: "+l+" key null = "+isAllNull(keyBuf)+" routing key null = "+isAllNull(routingkey)+" headers null = "+isAllNull(header)+" data null = "+isAllNull(data));
							System.err.println("Duplicate block: "+l+" key null = "+isAllNull(keyBuf)+" routing key null = "+isAllNull(routingkey)+" headers null = "+isAllNull(header)+" data null = "+isAllNull(data));
							dupes++;
							reconstructAddFreeBlock(l, t, --minLRU);
						}
						t.commitNoSync();
						t = null;
						continue;
					} else if(op != OperationStatus.SUCCESS) {
						addFreeBlock(l, true, "failure: "+op);
						failures++;
					}
					t.commitNoSync();
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

	private void reconstructAddFreeBlock(long l, Transaction t, long lru) throws DatabaseException {
		StoreBlock storeBlock = new StoreBlock(l, lru);
		byte[] buf = new byte[32];
		random.nextBytes(buf);
		DatabaseEntry routingkeyDBE = new DatabaseEntry(buf);
		DatabaseEntry blockDBE = new DatabaseEntry();
		storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
		OperationStatus op = keysDB.putNoOverwrite(t, routingkeyDBE, blockDBE);
		if (op != OperationStatus.SUCCESS) {
			Logger.error(this, "Impossible operation status inserting bogus key to LRU: " + op);
			addFreeBlock(l, true, "Impossible to add (invalid) to LRU: " + op);
		}
	}

	private boolean isAllNull(byte[] buf) {
		for(int i=0;i<buf.length;i++)
			if(buf[i] != 0) return false;
		return true;
	}

	private int runningFetches;
	
	/**
	 * {@inheritDoc}
	 */
	public T fetch(byte[] routingkey, byte[] fullKey, boolean dontPromote,
			boolean canReadClientCache, boolean canReadSlashdotCache, boolean ignoreOldBlocks, BlockMetadata meta) throws IOException {
		T retval = fetch(routingkey, fullKey, dontPromote, canReadClientCache, canReadSlashdotCache, (DSAPublicKey)null);
		return retval;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public T fetch(byte[] routingkey, byte[] fullKey, boolean dontPromote,
			boolean canReadClientCache, boolean canReadSlashdotCache, DSAPublicKey knownPublicKey) throws IOException {
		DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
		DatabaseEntry blockDBE = new DatabaseEntry();
		int running;
		synchronized(this) {
			if(closed)
				return null;
			running = runningFetches++;
		}
		
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
			if(logMINOR) Logger.minor(this, "Fetching "+HexUtil.bytesToHex(routingkey)+" dontPromote="+dontPromote+" for "+callback+" running fetches: "+running);
			if(c.getSearchKey(routingkeyDBE,blockDBE,LockMode.RMW)
					!=OperationStatus.SUCCESS) {
				c.close();
				c = null;
				t.abort();
				t = null;
				synchronized(this) {
					misses++;
				}
				if(logMINOR) Logger.minor(this, "Not found");
				return null;
			}

			StoreBlock storeBlock = storeBlockTupleBinding.entryToObject(blockDBE);
						
			T block = null;
			
			if(logMINOR) Logger.minor(this, "Reading block "+storeBlock.offset+"...");
			try {
				byte[] header = new byte[headerBlockSize];
				byte[] data = new byte[dataBlockSize];
				try {
					fcReadStore(storeBlock.offset, header, data);
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
				
				block = callback.construct(data, header, routingkey, fullKey, canReadClientCache, canReadSlashdotCache, null, knownPublicKey);
				
				// Write the key.
				byte[] newFullKey = block.getFullKey();
				if(keysRAF != null) {
					fcWriteKey(storeBlock.offset, newFullKey);
				}
				
				if(!Arrays.equals(block.getRoutingKey(), routingkey)) {
					
					synchronized(this) {
						misses++;
					}
					
					keysDB.delete(t, routingkeyDBE);
					
					// Insert the block into the index.
					// Set the LRU to minimum - 1.
					
					long lru = getMinRecentlyUsed(t) - 1;
					
					Logger.normal(this, "Does not verify (not the expected key), setting accessTime to "+lru+" for : "+HexUtil.bytesToHex(routingkey));
					
					storeBlock = new StoreBlock(storeBlock.offset, lru);
					
					routingkeyDBE = new DatabaseEntry(block.getRoutingKey());
					
					blockDBE = new DatabaseEntry();
					storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
					try {
						keysDB.put(t,routingkeyDBE,blockDBE);
						if(fullKey == null)
							fullKey = block.getFullKey();
						
							if(keysRAF != null) {
								fcWriteKey(storeBlock.offset, fullKey);
								if(logDEBUG)
									Logger.debug(this, "Written full key length "+fullKey.length+" to block "+storeBlock.offset+" at "+(storeBlock.offset * keyLength)+" for "+callback);
							} else if(logDEBUG) {
								Logger.debug(this, "Not writing full key length "+fullKey.length+" for block "+storeBlock.offset+" for "+callback);
							}
						
					} catch (DatabaseException e) {
						Logger.error(this, "Caught database exception "+e+" while replacing element");
						addFreeBlock(storeBlock.offset, true, "Bogus key");
						c.close();
						c = null;
						t.commit();
						t = null;
						return null;
					}
					Logger.normal(this, "Successfully replaced entry at block number "+storeBlock.offset+" lru "+lru);
					c.close();
					c = null;
					t.commit();
					t = null;
					return null;
				}
				
				if(!dontPromote) {
					storeBlock.updateRecentlyUsed(this);
					DatabaseEntry updateDBE = new DatabaseEntry();
					storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
					c.putCurrent(updateDBE);
					c.close();
					c = null;
					t.commit();
					t = null;
					fcWriteLRU(storeBlock.offset, storeBlock.recentlyUsed);
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
				
			} catch(KeyVerifyException ex) {
				Logger.normal(this, "Does not verify ("+ex+"), setting accessTime to 0 for : "+HexUtil.bytesToHex(routingkey), ex);
				synchronized(this) {
					misses++;
				}
				
					// Clear the key in the keys file.
					byte[] buf = new byte[keyLength];
					for(int i=0;i<buf.length;i++) buf[i] = 0; // FIXME unnecessary?
					if(keysRAF != null) {
						fcWriteKey(storeBlock.offset, buf);
					}
				
				keysDB.delete(t, routingkeyDBE);
				
				// Insert the block into the index with a random key, so that it's part of the LRU.
				// Set the LRU to minimum - 1.
				
				long lru = getMinRecentlyUsed(t) - 1;
				
				byte[] randomKey = new byte[keyLength];
				random.nextBytes(randomKey);
				
				storeBlock = new StoreBlock(storeBlock.offset, lru);
				
				routingkeyDBE = new DatabaseEntry(randomKey);
				
				blockDBE = new DatabaseEntry();
				storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
				try {
					keysDB.put(t,routingkeyDBE,blockDBE);
				} catch (DatabaseException e) {
					Logger.error(this, "Caught database exception "+e+" while adding corrupt element to LRU");
					addFreeBlock(storeBlock.offset, true, "Bogus key");
					c.close();
					c = null;
					t.commit();
					t = null;
					return null;
				}

				c.close();
				c = null;
				t.commit();
				t = null;
				return null;
			}
			synchronized(this) {
				hits++;
			}
			return block;
		} catch (ClosedChannelException cce) {
			// The channel is already close
			Logger.debug(this, "channel closed" , cce);
			return null;
		} catch(Throwable ex) {  // FIXME: ugly
			if(ex instanceof IOException) {
				synchronized(this) {
					if(closed) return null;
				}
			}
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
		} finally {
			int x;
			synchronized(this) {
				x = runningFetches--;
			}
			if(logMINOR) Logger.minor(this, "Running fetches now "+x);
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

	/**
	 * {@inheritDoc}
	 */
	public void put(StorableBlock block, byte[] data, byte[] header, boolean overwrite, boolean isOldBlock) throws KeyCollisionException, IOException {
		// We do not support flagging a block as old because we do not support block flags.
		byte[] routingkey = block.getRoutingKey();
		byte[] fullKey = block.getFullKey();
		
		if(logMINOR)
			Logger.minor(this, "Putting "+HexUtil.bytesToHex(routingkey)+" for "+callback);
		StorableBlock oldBlock = fetch(routingkey, fullKey, false, false, false, block instanceof SSKBlock ? ((SSKBlock)block).getPubKey() : null);
		if(oldBlock != null) {
			if(!collisionPossible) return;
			if(!block.equals(oldBlock)) {
				if(!overwrite)
					throw new KeyCollisionException();
				else
					overwriteKeyUnchanged(routingkey, fullKey, data, header);
			} // else return; // already in store
		} else {
			innerPut(block, routingkey, fullKey, data, header);
		}
	}
	
	/**
	 * Overwrite a block with a new block which has the same key.
	 */
	private boolean overwriteKeyUnchanged(byte[] routingkey, byte[] fullKey, byte[] data, byte[] header) throws IOException {
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

			StoreBlock storeBlock = storeBlockTupleBinding.entryToObject(blockDBE);
						
			fcWriteStore(storeBlock.offset, header, data);
			if (keysRAF != null) {
				fcWriteKey(storeBlock.offset, fullKey);
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
				t = null;
				if(fetch(routingkey, fullKey, false, false, false, block instanceof SSKBlock ? ((SSKBlock)block).getPubKey() : null) != null) return; // old key was valid, we are not overwriting
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
		} catch (ClosedChannelException cce) {
			// The channel is already close
			Logger.debug(this, "channel closed", cce);
		} catch(Throwable ex) {  // FIXME: ugly
			if(ex instanceof IOException) {
				synchronized(this) {
					if(closed) return;
				}
			}
			checkSecondaryDatabaseError(ex);
			Logger.error(this, "Caught "+ex, ex);
			ex.printStackTrace();
			if(ex instanceof IOException) throw (IOException) ex;
			else throw new IOException(ex.getMessage());
		} finally {
			if(t!=null){
				try{t.abort();}catch(DatabaseException ex2){}
			}
		}
	}
	
	private void overwriteLRUBlock(byte[] header, byte[] data, Transaction t, DatabaseEntry routingkeyDBE, byte[] fullKey) throws DatabaseException, IOException {
		// Overwrite an other block
		Cursor c = accessTimeDB.openCursor(t,null);
		StoreBlock oldStoreBlock;
		try {
			DatabaseEntry keyDBE = new DatabaseEntry();
			DatabaseEntry dataDBE = new DatabaseEntry();
			c.getFirst(keyDBE,dataDBE,LockMode.RMW);
			oldStoreBlock = storeBlockTupleBinding.entryToObject(dataDBE);
			c.delete();
		} finally {
			c.close();
		}
		// Deleted, so we can now reuse it.
		// Because we acquired a write lock, nobody else has taken it.
		// FIXME if this fails we can leak the block??
		StoreBlock storeBlock = new StoreBlock(this, oldStoreBlock.getOffset());
		DatabaseEntry blockDBE = new DatabaseEntry();
		storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
		keysDB.put(t,routingkeyDBE,blockDBE);
		
		fcWriteStore(storeBlock.getOffset(), header, data);
		fcWriteLRU( storeBlock.getOffset(),storeBlock.recentlyUsed);
		if (keysRAF != null)
			fcWriteKey(storeBlock.getOffset(), fullKey);
		synchronized (this) {
			writes++;
		}
	}

	private boolean writeNewBlock(long blockNum, byte[] header, byte[] data, Transaction t, DatabaseEntry routingkeyDBE, byte[] fullKey) throws DatabaseException, IOException {
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
		
			fcWriteStore(blockNum, header, data);
			fcWriteLRU(blockNum, lruValue);
			if(keysRAF != null) {
				fcWriteKey(blockNum, fullKey);
				if(logDEBUG)
					Logger.debug(this, "Written full key length "+fullKey.length+" to block "+blockNum+" at "+(blockNum * keyLength)+" for "+callback);
			} else if(logDEBUG) {
				Logger.debug(this, "Not writing full key length "+fullKey.length+" for block "+blockNum+" for "+callback);
			}
			synchronized (this) {
			writes++;
		}
		
		return true;
	}

	/**
	 * Get prefix name of this database (such as <tt>ssk-cache-</tt>,
	 * <tt>ssk-store-</tt>, etc).
	 */
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
				
				System.err.println("Flusing data store files (" + getName() + ")");
				flushAndCloseRAF(storeRAF);
				storeRAF = null;
				flushAndCloseRAF(lruRAF);
				lruRAF = null;
				flushAndCloseRAF(keysRAF);
				keysRAF = null;
				
				WrapperManager.restart();
				System.exit(freenet.node.NodeInitException.EXIT_DATABASE_REQUIRES_RESTART);
			} else if(ex instanceof DbChecksumException || ex instanceof RunRecoveryException || ex instanceof LogFileNotFoundException ||
					// UGH! We really shouldn't have to do this ... :(
					(msg != null && 
							(msg.indexOf("LogFileNotFoundException") >= 0 || msg.indexOf("DbChecksumException") >= 0
							|| msg.indexOf("RunRecoveryException") >= 0))) {
				System.err.println("Corrupt database! Will be reconstructed on restart");
				Logger.error(this, "Corrupt database! Will be reconstructed on restart");
				try {
					reconstructFile.createNewFile();
				} catch (IOException e) {
					Logger.error(this, "Corrupt database ("+getName()+") but could not create flag file "+reconstructFile);
					System.err.println("Corrupt database ("+getName()+") but could not create flag file "+reconstructFile);
					return; // Not sure what else we can do
				}
				
				System.err.println("Flusing data store files (" + getName() + ")");
				flushAndCloseRAF(storeRAF);
				storeRAF = null;
				flushAndCloseRAF(lruRAF);
				lruRAF = null;
				flushAndCloseRAF(keysRAF);
				keysRAF = null;
				
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

	private static class StoreBlock {
		private long recentlyUsed;
		private long offset;
		
		public StoreBlock(final BerkeleyDBFreenetStore<?> bdbfs, long offset) {
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
		
		public void updateRecentlyUsed(BerkeleyDBFreenetStore<?> bdbfs) {
			recentlyUsed = bdbfs.getNewRecentlyUsed();
		}
		
		public long getOffset() {
			return offset;
		}
	}
	
	/**
	* Convert StoreBlock's to the format used by the database
	*/
	private class StoreBlockTupleBinding extends TupleBinding<StoreBlock> {

		@Override
        public void objectToEntry(StoreBlock myData, TupleOutput to) {
			to.writeLong(myData.getOffset());
			to.writeLong(myData.getRecentlyUsed());
		}

		@Override
        public StoreBlock entryToObject(TupleInput ti) {
			long offset = ti.readLong();
			long lastAccessed = ti.readLong();
			
			StoreBlock storeBlock = new StoreBlock(offset,lastAccessed);
			return storeBlock;
		}
	}
	
	/**
	* Used to create the secondary database sorted on accesstime
	*/
	private static class AccessTimeKeyCreator implements SecondaryKeyCreator {
		private final TupleBinding<StoreBlock> theBinding;
		
		public AccessTimeKeyCreator(TupleBinding<StoreBlock> theBinding1) {
			theBinding = theBinding1;
		}
		
		public boolean createSecondaryKey(SecondaryDatabase secDb,
				DatabaseEntry keyEntry,
				DatabaseEntry dataEntry,
				DatabaseEntry resultEntry) {

			StoreBlock storeblock = theBinding.entryToObject(dataEntry);
			LongBinding.longToEntry(storeblock.getRecentlyUsed(), resultEntry);
			return true;
		}
	}

	private static class BlockNumberKeyCreator implements SecondaryKeyCreator {
		private final TupleBinding<StoreBlock> theBinding;
		
		public BlockNumberKeyCreator(TupleBinding<StoreBlock> theBinding1) {
			theBinding = theBinding1;
		}
		
		public boolean createSecondaryKey(SecondaryDatabase secDb,
				DatabaseEntry keyEntry,
				DatabaseEntry dataEntry,
				DatabaseEntry resultEntry) {

			StoreBlock storeblock = theBinding.entryToObject(dataEntry);
			LongBinding.longToEntry(storeblock.offset, resultEntry);
			return true;
		}
		
	}
	
	private class ShutdownHook extends NativeThread {
		
        public ShutdownHook() {
			super(name, NativeThread.HIGH_PRIORITY, true);
			// TODO Auto-generated constructor stub
		}

		public void realRun() {
			System.err.println("Closing database due to shutdown.");
			close(true);
		}
	}
	
	private final Object closeLock = new Object();

	private static void flushAndCloseRAF(RandomAccessFile file) {
		try {
			if (file != null)
				file.getChannel().force(true);
		} catch (IOException e) {
			// ignore
		}
		closeRAF(file, false);
	}

	private static void closeRAF(RandomAccessFile file, boolean logError) {
			try {
						if (file != null)
								file.close();
				} catch (IOException e) {
						if (logError) {
								System.err.println("Caught closing file: " + e);
								e.printStackTrace();
						}
				}
		}

	private static void closeDB(Database db, boolean logError) {
				try {
						if (db != null)
								db.close();
				} catch (DatabaseException e) {
						if (logError) {
								System.err.println("Caught closing database: " + e);
								e.printStackTrace();
						}
				}
		}
	
	private void close(boolean sleep) {
		try {
			// FIXME: 	we should be sure all access to the database has stopped
			//			before we try to close it. Currently we just guess
			//			This is nothing too problematic however since the worst thing that should
			//			happen is that we miss the last few store()'s and get an exception.
			logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			if(logMINOR) Logger.minor(this, "Closing database "+this);
			
			synchronized (this) {
				closed = true;
			}

			// Give all threads some time to complete
			if(sleep)
				try {
				Thread.sleep(5000);
				} catch (InterruptedException ie) {
					Logger.error(this, "Thread interrupted.", ie);
				}
			
			if(reallyClosed) {
				Logger.error(this, "Already closed "+this);
				return;
			}
			
			synchronized(closeLock) {
				if(reallyClosed) {
					Logger.error(this, "Already closed "+this);
					return;
				}
				
				closeRAF(storeRAF, true);
				storeRAF = null;
				closeRAF(lruRAF, true);
				lruRAF = null;
				closeRAF(keysRAF, true);
				keysRAF = null;

				closeDB(accessTimeDB, true);
				accessTimeDB = null;
				closeDB(blockNumDB, true);
				blockNumDB = null;
				closeDB(keysDB, true);
				keysDB = null;

				if(logMINOR) Logger.minor(this, "Closing database finished.");
				System.err.println("Closed database");
			}
		} catch (RuntimeException ex) {
			Logger.error(this, "Error while closing database.", ex);
			ex.printStackTrace();
		} finally {
			reallyClosed = true;
		}
	}
	
	private long highestBlockNumberInDatabase() throws DatabaseException {
		Cursor c = null;
		try {
			c = blockNumDB.openCursor(null,null);
			DatabaseEntry keyDBE = new DatabaseEntry();
			DatabaseEntry dataDBE = new DatabaseEntry();
			if(c.getLast(keyDBE,dataDBE,null)==OperationStatus.SUCCESS) {
				StoreBlock storeBlock = storeBlockTupleBinding.entryToObject(dataDBE);
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
				StoreBlock storeBlock = storeBlockTupleBinding.entryToObject(dataDBE);
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
	
	private long getMinRecentlyUsed(Transaction t) {
		long minRecentlyUsed = 0;
		
		Cursor c = null;
		try {
			c = accessTimeDB.openCursor(t,null);
			DatabaseEntry keyDBE = new DatabaseEntry();
			DatabaseEntry dataDBE = new DatabaseEntry();
			if(c.getFirst(keyDBE,dataDBE,null)==OperationStatus.SUCCESS) {
				StoreBlock storeBlock = storeBlockTupleBinding.entryToObject(dataDBE);
				minRecentlyUsed = storeBlock.getRecentlyUsed();
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
		
		return minRecentlyUsed;
	}
	
	private long getNewRecentlyUsed() {
		synchronized(lastRecentlyUsedSync) {
			lastRecentlyUsed++;
			return lastRecentlyUsed;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public void setMaxKeys(long maxStoreKeys, boolean forceBigShrink) throws DatabaseException, IOException {
		synchronized(this) {
			maxBlocksInStore = maxStoreKeys;
		}
		maybeOnlineShrink(false);
	}

	/**
	 * {@inheritDoc}
	 */
	public long getMaxKeys() {
		return maxBlocksInStore;
	}

	/**
	 * {@inheritDoc}
	 */
	public long hits() {
		return hits;
	}

	/**
	 * {@inheritDoc}
	 */
	public long misses() {
		return misses;
	}

	/**
	 * {@inheritDoc}
	 */
	public long writes() {
		return writes;
	}

	/**
	 * {@inheritDoc}
	 */
	public long keyCount() {
		return blocksInStore;
	}

	/**
	 * Remove all secondary database of this datastore.
	 * 
	 * @throws DatabaseException
	 */
	private void removeSecondaryDatabase() throws DatabaseException {
		Logger.error(this, "Recreating secondary databases");
		Logger.error(this, "This may take some time...");
		System.err.println("Recreating secondary databases");
		System.err.println("This may take some time...");

		WrapperManager.signalStarting((int) (Math.min(Integer.MAX_VALUE, 5 * 60 * 1000 + keysDB.count() * 100L)));

		// Of course it's not a solution but a quick fix
		// Integer.MAX_VALUE seems to trigger an overflow or whatever ...
		// Either we find out what the maximum value is and we do a static method somewhere ensuring
		// it won't overflow ... or we debug the wrapper.
		// NB: it might be a wrapper-version-mismatch problem (nextgens)
		try {
			try {
				environment.removeDatabase(null, name+"CHK_accessTime");
			} catch (DatabaseNotFoundException e) {
				// Cool!
			}
			try {
				environment.removeDatabase(null, name+"CHK_blockNum");
			} catch (DatabaseNotFoundException e) {
				// Cool!
			}
		} catch (DatabaseException e) {
			close(false);
			throw e;
		}
	}

	/**
	 * Open a secondary database of this datastore.
	 * 
	 * @param dbName
	 *                Full database name
	 * @param create
	 *                <code>true</code> if allowed to create a new
	 *                secondary database, <code>false</code> otherwise.
	 * @param populate
	 *                <code>true</code> if populate this secondary
	 *                database automatically, <code>false</code>
	 *                otherwise.
	 * @param secondaryKeyCreator
	 *                {@link SecondaryKeyCreator} for this secondary
	 *                database instance.
	 * @return
	 * @throws DatabaseException
	 */
	private SecondaryDatabase openSecondaryDataBase(String dbName, boolean create, boolean populate,
			boolean sortedDuplicates, SecondaryKeyCreator secondaryKeyCreator) throws DatabaseException {
		SecondaryDatabase db = null;
		SecondaryConfig secDbConfig = new SecondaryConfig();
		secDbConfig.setAllowCreate(create);
		secDbConfig.setSortedDuplicates(sortedDuplicates);
		secDbConfig.setTransactional(true);
		secDbConfig.setAllowPopulate(populate);
		secDbConfig.setKeyCreator(secondaryKeyCreator);

		try {
			try {
				System.err.println("Opening secondary database: " + dbName );
				db = environment.openSecondaryDatabase(null, dbName, keysDB, secDbConfig);
				// The below is too slow to be useful, because SecondaryDatabase.count() isn't optimised.
				//				long chkDBCount = keysDB.count();
				//				System.err.println("Counting size of access times database...");
				//				long dbCount = db.count();
				//				if(dbCount < chkDBCount) {
				//					System.err.println("Access times database: "+dbCount+" but main database: "+chkDBCount);
				//					throw new DatabaseException("Needs repopulation");
				//				}
			} catch (DatabaseException e) {
				// failed, try to create it.

				WrapperManager.signalStarting((int) (Math
						.min(Integer.MAX_VALUE, 5 * 60 * 1000L + keysDB.count() * 100L)));
				// Of course it's not a solution but a quick fix
				// Integer.MAX_VALUE seems to trigger an overflow or whatever ...
				// Either we find out what the maximum value is and we do a static method somewhere ensuring
				// it won't overflow ... or we debug the wrapper.
				// NB: it might be a wrapper-version-missmatch problem (nextgens)

				System.err.println("Reconstructing index for secondary database: " + dbName);
				Logger.error(this,"Reconstructing index for secondary database: " + dbName);
				if (db != null)
					db.close();
				try {
					environment.removeDatabase(null, dbName);
				} catch (DatabaseNotFoundException e1) {
					// Ok
				}
				secDbConfig.setAllowCreate(true);
				secDbConfig.setAllowPopulate(true);
				db = environment.openSecondaryDatabase(null, dbName, keysDB, secDbConfig);
			}
		} catch (DatabaseException e1) {
			// Log this now because close() will probably throw too
			System.err.println("Error opening secondary database: " + dbName);
			e1.printStackTrace();
			Logger.error(this,
					"Error opening secondary database: " + dbName + " (" + e1.getMessage() + ")",
					e1);
			close(false);
			throw e1;
		}
		System.err.println("Opened secondary database: " + dbName );

		return db;
	}

	private void fcWriteLRU(long entry, long data) throws IOException {
		ByteBuffer bf = ByteBuffer.allocate(8);
		bf.putLong(data);
		bf.flip();
		do {
			int byteWritten = lruFC.write(bf, entry * 8 + bf.position());
			if (byteWritten == -1)
				throw new EOFException();
		} while (bf.hasRemaining());
	}
	private long fcReadLRU(long entry) throws IOException {
		ByteBuffer bf = ByteBuffer.allocate(8);
		do {
			int byteRead = lruFC.read(bf, entry * 8 + bf.position());
			if (byteRead == -1)
				throw new EOFException();
		} while (bf.hasRemaining());
		bf.flip();
		return bf.getLong();
	}
	private void fcReadKey(long entry, byte[] data) throws IOException {	
		ByteBuffer bf = ByteBuffer.wrap(data);
		do {
			int byteRead = keysFC.read(bf, entry * keyLength + bf.position());
			if (byteRead == -1)
				throw new EOFException();
		} while (bf.hasRemaining());
	}
	private void fcWriteKey(long entry, byte[] data) throws IOException {
		assert(data.length == keyLength);
		ByteBuffer bf = ByteBuffer.wrap(data);
		do {
			int byteWritten = keysFC.write(bf, entry * keyLength + bf.position());
			if (byteWritten == -1)
				throw new EOFException();
		} while (bf.hasRemaining());
	}
	private void fcWriteStore(long entry, byte[] header, byte[] data) throws IOException {
		ByteBuffer bf = ByteBuffer.allocate(headerBlockSize + dataBlockSize);
		bf.put(header);
		bf.put(data);
		bf.flip();
		do {
			int byteWritten = storeFC.write(bf, (headerBlockSize + dataBlockSize) * entry + bf.position());
			if (byteWritten == -1)
				throw new EOFException();
		} while (bf.hasRemaining());
	}
	private void fcReadStore(long entry,byte[] header, byte[] data ) throws IOException {
		ByteBuffer bf = ByteBuffer.allocate(headerBlockSize + dataBlockSize);
		
		do {
			int dataRead = storeFC.read(bf, (headerBlockSize + dataBlockSize) * entry);
			if (dataRead == -1)
				throw new EOFException();
		} while (bf.hasRemaining());
		
		bf.flip();
		bf.get(header);
		bf.get(data);
	}
	
    public void handleLowMemory() throws Exception {
    	// Flush all
		if (storeFC != null)
			storeFC.force(true);
		if (keysFC != null)
			keysFC.force(true);
		if (lruFC != null)
			lruFC.force(true);
		
		environment.evictMemory();
	}

	public void handleOutOfMemory() throws Exception {
		// database likely to be corrupted,
		// reconstruct it just in case
		reconstructFile.createNewFile();
		
		// Flush all
		if (storeFC != null)
			storeFC.force(true);
		if (keysFC != null)
			keysFC.force(true);
		if (lruFC != null)
			lruFC.force(true);
	}
	
	/**
     * @return
     */
    public static EnvironmentConfig getBDBConfig() {
        // First, global settings
    	
    	// Percentage of the database that must contain usefull data
    	// decrease to increase performance, increase to save disk space
    	// Let it stay at the default of 50% for best performance.
    	// We only use it for indexes, so it won't get huge.
    	//System.setProperty("je.cleaner.minUtilization","90");
    	// Delete empty log files
    	System.setProperty("je.cleaner.expunge","true");
    	EnvironmentConfig envConfig = new EnvironmentConfig();
    	envConfig.setAllowCreate(true);
    	envConfig.setTransactional(true);
    	envConfig.setTxnWriteNoSync(true);
    	envConfig.setLockTimeout(600*1000*1000); // should be long enough even for severely overloaded nodes!
    	// Note that the above is in *MICRO*seconds.
    	envConfig.setConfigParam("je.log.faultReadSize", "6144");
    	// http://www.oracle.com/technology/products/berkeley-db/faq/je_faq.html#35
    	envConfig.setConfigParam("je.evictor.lruOnly", "false");  //Is not a mutable config option and must be set before opening of environment.
    	envConfig.setConfigParam("je.evictor.nodesPerScan", "50");  //Is not a mutable config option and must be set before opening of environment.
    	// Recommended is 100, but smaller values reduce latency cost.
    	
    	// Tune latency
    	envConfig.setConfigParam("je.env.backgroundReadLimit", "65536");
    	envConfig.setConfigParam("je.env.backgroundWriteLimit", "65536");
    	envConfig.setConfigParam("je.env.backgroundSleepInterval", "10000" /* microseconds */); // 10ms
        return envConfig;
    }

	public long getBloomFalsePositive() {
		return -1;
	}
	
    public boolean probablyInStore(byte[] routingKey) {
    	// This needs to be fast, so that it can be run from any thread.
    	// Accessing the bdbje database is often slow, involves many disk seeks,
    	// and can stall for long periods.
    	return true;
		/*-
		DatabaseEntry routingkeyDBE = new DatabaseEntry(routingKey);
		DatabaseEntry blockDBE = new DatabaseEntry();
		synchronized (this) {
			if (closed)
				return false;
		}

		try {
			return keysDB.get(null, routingkeyDBE, blockDBE, LockMode.READ_UNCOMMITTED) == OperationStatus.SUCCESS;
		} catch (DatabaseException e) {
			return false;
		} 
		 */
	}
    
	public StoreAccessStats getSessionAccessStats() {
		return new StoreAccessStats() {

			@Override
			public long hits() {
				return hits;
			}

			@Override
			public long misses() {
				return misses;
			}

			@Override
			public long falsePos() {
				return 0;
			}

			@Override
			public long writes() {
				return writes;
			}
			
		};
	}

	public StoreAccessStats getTotalAccessStats() {
		return null;
	}

}
