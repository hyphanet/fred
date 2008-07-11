/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.store.saltedhash;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.keys.KeyVerifyException;
import freenet.node.SemiOrderedShutdownHook;
import freenet.store.FreenetStore;
import freenet.store.KeyCollisionException;
import freenet.store.StorableBlock;
import freenet.store.StoreCallback;
import freenet.support.BloomFilter;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.io.FileUtil;

/**
 * Index-less data store based on salted hash
 * 
 * @author sdiz
 */
public class SaltedHashFreenetStore implements FreenetStore {
	/** Option for saving plainkey */
	private static final boolean OPTION_SAVE_PLAINKEY = true;
	private static final int OPTION_MAX_PROBE = 4;

	private static final byte FLAG_DIRTY = 0x1;
	private static final byte FLAG_REBUILD_BLOOM = 0x2;

	private static final int BLOOM_FILTER_SIZE = 0x10000000; // 128Mib = 16MiB
	private static final boolean updateBloom = true;
	private static boolean checkBloom = true;
	private int bloomFilterK;
	private BloomFilter bloomFilter;

	private static boolean logMINOR;
	private static boolean logDEBUG;

	private final File baseDir;
	private final String name;
	private final StoreCallback callback;
	private final boolean collisionPossible;
	private final int headerBlockLength;
	private final int routeKeyLength;
	private final int fullKeyLength;
	private final int dataBlockLength;
	private final Random random;
	private long storeSize;
	private int generation;
	private int flags;

	public static SaltedHashFreenetStore construct(File baseDir, String name, StoreCallback callback, Random random,
	        long maxKeys, SemiOrderedShutdownHook shutdownHook) throws IOException {
		return new SaltedHashFreenetStore(baseDir, name, callback, random, maxKeys, shutdownHook);
	}

	private SaltedHashFreenetStore(File baseDir, String name, StoreCallback callback, Random random, long maxKeys,
	        SemiOrderedShutdownHook shutdownHook) throws IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		logDEBUG = Logger.shouldLog(Logger.DEBUG, this);

		this.baseDir = baseDir;
		this.name = name;

		this.callback = callback;
		collisionPossible = callback.collisionPossible();
		routeKeyLength = callback.routingKeyLength();
		headerBlockLength = callback.headerLength();
		fullKeyLength = callback.fullKeyLength();
		dataBlockLength = callback.dataLength();

		this.random = random;
		storeSize = maxKeys;

		lockManager = new LockManager();

		// Create a directory it not exist
		this.baseDir.mkdirs();

		configFile = new File(this.baseDir, name + ".config");
		loadConfigFile();

		openStoreFiles(baseDir, name);

		if (updateBloom || checkBloom) {
			File bloomFile = new File(this.baseDir, name + ".bloom");
			if (!bloomFile.exists() || bloomFile.length() != BLOOM_FILTER_SIZE / 8) {
				flags |= FLAG_REBUILD_BLOOM;
				checkBloom = false;
			}
			bloomFilter = new BloomFilter(bloomFile, BLOOM_FILTER_SIZE, bloomFilterK);
		}

		if ((flags & FLAG_DIRTY) != 0)
			System.err.println("Datastore(" + name + ") is dirty.");

		flags |= FLAG_DIRTY; // datastore is now dirty until flushAndClose()
		writeConfigFile();

		if (maxKeys != storeSize) {
			if (prevStoreSize != 0) {
				storeSize = Math.max(prevStoreSize, storeSize);
				prevStoreSize = 0;
			}
			setMaxKeys(maxKeys, true);
		}

		callback.setStore(this);
		shutdownHook.addEarlyJob(new Thread(new ShutdownDB()));

		cleanerThread = new Cleaner();

		if (cleanerGlobalLock.tryLock()) {
			migrateFromOldSaltedHash(); // XXX Old Format, to be removed in next build
			cleanerGlobalLock.unlock();
		}
		
		cleanerThread.start();
	}

	public StorableBlock fetch(byte[] routingKey, byte[] fullKey, boolean dontPromote) throws IOException {
		if (logMINOR)
			Logger.minor(this, "Fetch " + HexUtil.bytesToHex(routingKey) + " for " + callback);

		configLock.readLock().lock();
		try {
			boolean locked = lockPlainKey(routingKey, true);
			if (!locked) {
				if (logDEBUG)
					Logger.debug(this, "cannot lock key: " + HexUtil.bytesToHex(routingKey) + ", shutting down?");
				return null;
			}
			try {
				Entry entry = probeEntry(routingKey, true);

				if (entry == null) {
					misses.incrementAndGet();
					return null;
				}

				try {
					StorableBlock block = entry.getStorableBlock(routingKey, fullKey);
					if (block == null) {
						misses.incrementAndGet();
						return null;
					}
					hits.incrementAndGet();
					return block;
				} catch (KeyVerifyException e) {
					Logger.minor(this, "key verification exception", e);
					misses.incrementAndGet();
					return null;
				}
			} finally {
				unlockPlainKey(routingKey, true);
			}
		} finally {
			configLock.readLock().unlock();
		}
	}

	/**
	 * Find and lock an entry with a specific routing key. This function would <strong>not</strong>
	 * lock the entries.
	 * 
	 * @param routingKey
	 * @param withData
	 * @return <code>Entry</code> object
	 * @throws IOException
	 */
	private Entry probeEntry(byte[] routingKey, boolean withData) throws IOException {
		if (checkBloom)
			if (!bloomFilter.checkFilter(cipherManager.getDigestedKey(routingKey)))
				return null;

		Entry entry = probeEntry0(routingKey, storeSize, withData);

		if (entry == null && prevStoreSize != 0)
			entry = probeEntry0(routingKey, prevStoreSize, withData);
		if (checkBloom && entry == null)
			bloomFalsePos.incrementAndGet();

		return entry;
	}

	private Entry probeEntry0(byte[] routingKey, long probeStoreSize, boolean withData) throws IOException {
		Entry entry = null;
		long[] offset = getOffsetFromPlainKey(routingKey, probeStoreSize);

		for (int i = 0; i < offset.length; i++) {
			if (logDEBUG)
				Logger.debug(this, "probing for i=" + i + ", offset=" + offset[i]);

			try {
				entry = readEntry(offset[i], routingKey, withData);
				if (entry != null)
					return entry;
			} catch (EOFException e) {
				if (prevStoreSize != 0) // may occur on store shrinking
					Logger.error(this, "EOFException on probeEntry", e);
				continue;
			}
		}
		return null;
	}

	public void put(StorableBlock block, byte[] routingKey, byte[] fullKey, byte[] data, byte[] header,
	        boolean overwrite) throws IOException, KeyCollisionException {
		if (logMINOR)
			Logger.minor(this, "Putting " + HexUtil.bytesToHex(routingKey) + " (" + name + ")");

		configLock.readLock().lock();
		try {
			boolean locked = lockPlainKey(routingKey, false);
			if (!locked) {
				if (logDEBUG)
					Logger.debug(this, "cannot lock key: " + HexUtil.bytesToHex(routingKey) + ", shutting down?");
				return;
			}
			try {
				/*
				 * Use lazy loading here. This may lost data if digestedRoutingKey collide but
				 * collisionPossible is false. Should be very rare as digestedRoutingKey is a
				 * SHA-256 hash.
				 */
				Entry oldEntry = probeEntry(routingKey, false);
				if (oldEntry != null) {
					long oldOffset = oldEntry.curOffset;
					try {
						if (!collisionPossible)
							return;
						oldEntry.setData(readHeader(oldOffset), readData(oldOffset)); // read from disk
						StorableBlock oldBlock = oldEntry.getStorableBlock(routingKey, fullKey);
						if (block.equals(oldBlock)) {
							return; // already in store
						} else if (!overwrite) {
							throw new KeyCollisionException();
						}
					} catch (KeyVerifyException e) {
						// ignore
					}

					// Overwrite old offset
					Entry entry = new Entry(routingKey, header, data);
					writeEntry(entry, oldOffset);
					writes.incrementAndGet();
					if (oldEntry.generation != generation)
						keyCount.incrementAndGet();
					return;
				}

				Entry entry = new Entry(routingKey, header, data);
				long[] offset = entry.getOffset();

				for (int i = 0; i < offset.length; i++) {
					if (isFree(offset[i])) {
						// write to free block
						if (logDEBUG)
							Logger.debug(this, "probing, write to i=" + i + ", offset=" + offset[i]);
						if (updateBloom)
							bloomFilter.updateFilter(cipherManager.getDigestedKey(routingKey));
						writeEntry(entry, offset[i]);
						writes.incrementAndGet();
						keyCount.incrementAndGet();

						return;
					}
				}

				// no free blocks, overwrite the first one
				if (logDEBUG)
					Logger.debug(this, "collision, write to i=0, offset=" + offset[0]);
				if (updateBloom)
					bloomFilter.updateFilter(cipherManager.getDigestedKey(routingKey));
				oldEntry = readEntry(offset[0], null, false);
				writeEntry(entry, offset[0]);
				writes.incrementAndGet();
				if (oldEntry.generation != generation)
					keyCount.incrementAndGet();
			} finally {
				unlockPlainKey(routingKey, false);
			}
		} finally {
			configLock.readLock().unlock();
		}
	}

	// ------------- Entry I/O
	// meta-data file
	private File metaFile;
	private RandomAccessFile metaRAF;
	private FileChannel metaFC;
	// header file
	private File headerFile;
	private RandomAccessFile headerRAF;
	private FileChannel headerFC;
	// data file
	private File dataFile;
	private RandomAccessFile dataRAF;
	private FileChannel dataFC;

	/**
	 * Data entry
	 * 
	 * <pre>
	 *  META-DATA BLOCK
	 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *       |0|1|2|3|4|5|6|7|8|9|A|B|C|D|E|F|
	 *  +----+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *  |0000|                               |
	 *  +----+     Digested Routing Key      |
	 *  |0010|                               |
	 *  +----+-------------------------------+
	 *  |0020|       Data Encrypt IV         |
	 *  +----+---------------+---------------+
	 *  |0030|     Flag      |  Store Size   |
	 *  +----+---------------+---------------+
	 *  |0040|       Plain Routing Key       |
	 *  |0050| (Only if ENTRY_FLAG_PLAINKEY) |
	 *  +----+-------+-----------------------+
	 *  |0060|  Gen  |    Reserved           |
	 *  +----+-------+-----------------------+
	 *  |0070|            Reserved           |
	 *  +----+-------------------------------+
	 *  
	 *  Gen = Generation
	 * </pre>
	 */
	class Entry {
		/** Flag for occupied space */
		private final static long ENTRY_FLAG_OCCUPIED = 0x00000001L;
		/** Flag for plain key available */
		private final static long ENTRY_FLAG_PLAINKEY = 0x00000002L;

		/** Control block length */
		private static final int METADATA_LENGTH = 0x80;

		byte[] plainRoutingKey;
		byte[] digestedRoutingKey;
		byte[] dataEncryptIV;
		private long flag;
		private long storeSize;
		private int generation;
		byte[] header;
		byte[] data;

		boolean isEncrypted;
		private long curOffset = -1;

		private Entry() {
		}

		private Entry(ByteBuffer metaDataBuf, ByteBuffer headerBuf, ByteBuffer dataBuf) {
			assert metaDataBuf.remaining() == METADATA_LENGTH;

			digestedRoutingKey = new byte[0x20];
			metaDataBuf.get(digestedRoutingKey);

			dataEncryptIV = new byte[0x10];
			metaDataBuf.get(dataEncryptIV);

			flag = metaDataBuf.getLong();
			storeSize = metaDataBuf.getLong();

			if ((flag & ENTRY_FLAG_PLAINKEY) != 0) {
				plainRoutingKey = new byte[0x20];
				metaDataBuf.get(plainRoutingKey);
			}

			metaDataBuf.position(0x60);
			generation = metaDataBuf.getInt();

			isEncrypted = true;

			if (headerBuf != null && dataBuf != null)
				setData(headerBuf, dataBuf);
		}

		/**
		 * Set header/data after construction.
		 * 
		 * @param storeBuf
		 * @param store
		 */
		private void setData(ByteBuffer headerBuf, ByteBuffer dataBuf) {
			assert headerBuf.remaining() == headerBlockLength;
			assert dataBuf.remaining() == dataBlockLength;
			assert isEncrypted;

			header = new byte[headerBlockLength];
			headerBuf.get(header);

			data = new byte[dataBlockLength];
			dataBuf.get(data);
		}

		/**
		 * Create a new entry
		 * 
		 * @param plainRoutingKey
		 * @param header
		 * @param data
		 */
		private Entry(byte[] plainRoutingKey, byte[] header, byte[] data) {
			this.plainRoutingKey = plainRoutingKey;

			flag = ENTRY_FLAG_OCCUPIED;
			this.storeSize = SaltedHashFreenetStore.this.storeSize;
			this.generation = SaltedHashFreenetStore.this.generation;

			// header/data will be overwritten in encrypt()/decrypt(),
			// let's make a copy here
			this.header = new byte[headerBlockLength];
			System.arraycopy(header, 0, this.header, 0, headerBlockLength);
			this.data = new byte[dataBlockLength];
			System.arraycopy(data, 0, this.data, 0, dataBlockLength);

			if (OPTION_SAVE_PLAINKEY) {
				flag |= ENTRY_FLAG_PLAINKEY;
			}

			isEncrypted = false;
		}

		private ByteBuffer toMetaDataBuffer() {
			ByteBuffer out = ByteBuffer.allocate(METADATA_LENGTH);
			cipherManager.encrypt(this, random);

			out.put(getDigestedRoutingKey());
			out.put(dataEncryptIV);
			out.putLong(flag);
			out.putLong(storeSize);

			if ((flag & ENTRY_FLAG_PLAINKEY) != 0 && plainRoutingKey != null) {
				assert plainRoutingKey.length == 0x20;
				out.put(plainRoutingKey);
			}

			out.position(0x60);
			out.putInt(generation);

			out.position(0);
			return out;
		}

		private ByteBuffer toHeaderBuffer() {
			assert isEncrypted; // should have encrypted to get dataEncryptIV in control buffer

			if (header == null)
				return null;

			ByteBuffer out = ByteBuffer.allocate(headerBlockLength);
			out.put(header);
			assert out.remaining() == 0;

			out.position(0);
			return out;
		}

		private ByteBuffer toDataBuffer() {
			assert isEncrypted; // should have encrypted to get dataEncryptIV in control buffer

			if (data == null)
				return null;

			ByteBuffer out = ByteBuffer.allocate(dataBlockLength);
			out.put(data);
			assert out.remaining() == 0;

			out.position(0);
			return out;
		}

		private StorableBlock getStorableBlock(byte[] routingKey, byte[] fullKey) throws KeyVerifyException {
			if (isFree() || header == null || data == null)
				return null; // this is a free block
			if (!cipherManager.decrypt(this, routingKey))
				return null;

			StorableBlock block = callback.construct(data, header, routingKey, fullKey);
			byte[] blockRoutingKey = block.getRoutingKey();

			if (!Arrays.equals(blockRoutingKey, routingKey)) {
				// can't recover, as decrypt() depends on a correct route key
				return null;
			}

			return block;
		}

		private long[] getOffset() {
			if (digestedRoutingKey != null)
				return getOffsetFromDigestedKey(digestedRoutingKey, storeSize);
			else
				return getOffsetFromPlainKey(plainRoutingKey, storeSize);
		}

		private boolean isFree() {
			return (flag & ENTRY_FLAG_OCCUPIED) == 0;
		}

		byte[] getDigestedRoutingKey() {
			if (digestedRoutingKey == null)
				if (plainRoutingKey == null)
					return null;
				else
					digestedRoutingKey = cipherManager.getDigestedKey(plainRoutingKey);
			return digestedRoutingKey;
		}
		

		// XXX Old Format, to be removed in next build
		public void readOldFormat(ByteBuffer in) {
			assert in.remaining() == entryTotalLength;

			digestedRoutingKey = new byte[0x20];
			in.get(digestedRoutingKey);

			dataEncryptIV = new byte[0x10];
			in.get(dataEncryptIV);

			flag = in.getLong();
			storeSize = in.getLong();

			if ((flag & ENTRY_FLAG_PLAINKEY) != 0) {
				plainRoutingKey = new byte[0x20];
				in.get(plainRoutingKey);
			}

			in.position(0x60);
			generation = in.get();

			// reserved bytes
			in.position((int) ENTRY_HEADER_LENGTH);

			header = new byte[headerBlockLength];
			in.get(header);

			data = new byte[dataBlockLength];
			in.get(data);

			assert in.remaining() == entryPaddingLength;

			isEncrypted = true;
		}
	}

	/**
	 * Open all store files
	 * 
	 * @param baseDir
	 * @param name
	 * @throws IOException
	 */
	private void openStoreFiles(File baseDir, String name) throws IOException {
		metaFile = new File(baseDir, name + ".metadata");
		metaRAF = new RandomAccessFile(metaFile, "rw");
		metaFC = metaRAF.getChannel();
		metaFC.lock();

		headerFile = new File(baseDir, name + ".header");
		headerRAF = new RandomAccessFile(headerFile, "rw");
		headerFC = headerRAF.getChannel();
		headerFC.lock();

		dataFile = new File(baseDir, name + ".data");
		dataRAF = new RandomAccessFile(dataFile, "rw");
		dataFC = dataRAF.getChannel();
		dataFC.lock();

		long storeFileSize = Math.max(storeSize, prevStoreSize);
		WrapperManager.signalStarting(10 * 60 * 1000); // 10minutes, for filesystem that support no sparse file.
		setStoreFileSize(storeFileSize);
	}

	/**
	 * Read entry from disk.
	 * 
	 * Before calling this function, you should acquire all required locks.
	 */
	private Entry readEntry(long offset, byte[] routingKey, boolean withData) throws IOException {
		ByteBuffer mbf = ByteBuffer.allocate(Entry.METADATA_LENGTH);

		do {
			int status = metaFC.read(mbf, Entry.METADATA_LENGTH * offset + mbf.position());
			if (status == -1)
				throw new EOFException();
		} while (mbf.hasRemaining());
		mbf.flip();

		Entry entry = new Entry(mbf, null, null);
		entry.curOffset = offset;

		if (entry.isFree())
			return entry; // don't read free entry

		if (routingKey != null) {
			if (!Arrays.equals(cipherManager.getDigestedKey(routingKey), entry.digestedRoutingKey))
				return null;

			if (withData) {
				ByteBuffer headerBuf = readHeader(offset);
				ByteBuffer dataBuf = readData(offset);
				entry.setData(headerBuf, dataBuf);
				boolean decrypted = cipherManager.decrypt(entry, routingKey);
				if (!decrypted)
					return null;
			}
		}

		return entry;
	}

	/**
	 * Read header from disk
	 * 
	 * @param offset
	 * @throws IOException
	 */
	private ByteBuffer readHeader(long offset) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(headerBlockLength);

		do {
			int status = headerFC.read(buf, headerBlockLength * offset + buf.position());
			if (status == -1)
				throw new EOFException();
		} while (buf.hasRemaining());
		buf.flip();
		return buf;
	}

	/**
	 * Read data from disk
	 * 
	 * @param offset
	 * @throws IOException
	 */
	private ByteBuffer readData(long offset) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(dataBlockLength);

		do {
			int status = dataFC.read(buf, dataBlockLength * offset + buf.position());
			if (status == -1)
				throw new EOFException();
		} while (buf.hasRemaining());
		buf.flip();
		return buf;
	}

	private boolean isFree(long offset) throws IOException {
		Entry entry = readEntry(offset, null, false);
		return entry.isFree();
	}

	private byte[] getDigestedKeyFromOffset(long offset) throws IOException {
		Entry entry = readEntry(offset, null, false);
		return entry.getDigestedRoutingKey();
	}

	/**
	 * Write entry to disk.
	 * 
	 * Before calling this function, you should:
	 * <ul>
	 * <li>acquire all required locks</li>
	 * <li>update the entry with latest store size</li>
	 * </ul>
	 */
	private void writeEntry(Entry entry, long offset) throws IOException {
		cipherManager.encrypt(entry, random);

		ByteBuffer bf = entry.toMetaDataBuffer();
		do {
			int status = metaFC.write(bf, Entry.METADATA_LENGTH * offset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());

		bf = entry.toHeaderBuffer();
		if (bf != null) {
			do {
				int status = headerFC.write(bf, headerBlockLength * offset + bf.position());
				if (status == -1)
					throw new EOFException();
			} while (bf.hasRemaining());

			bf = entry.toDataBuffer();
			do {
				int status = dataFC.write(bf, dataBlockLength * offset + bf.position());
				if (status == -1)
					throw new EOFException();
			} while (bf.hasRemaining());
		}

		entry.curOffset = offset;
	}

	private void flushAndClose() {
		try {
			metaFC.force(true);
			metaFC.close();
		} catch (Exception e) {
			Logger.error(this, "error flusing store", e);
		}
		try {
			headerFC.force(true);
			headerFC.close();
		} catch (Exception e) {
			Logger.error(this, "error flusing store", e);
		}
		try {
			dataFC.force(true);
			dataFC.close();
		} catch (Exception e) {
			Logger.error(this, "error flusing store", e);
		}

		if (bloomFilter != null)
			bloomFilter.force();
	}

	/**
	 * Change on disk store file size
	 * 
	 * @param storeFileSize
	 */
	private void setStoreFileSize(long storeFileSize) {
		try {
			metaRAF.setLength(Entry.METADATA_LENGTH * storeFileSize);
			headerRAF.setLength(headerBlockLength * storeFileSize);
			dataRAF.setLength(dataBlockLength * storeFileSize);
		} catch (IOException e) {
			Logger.error(this, "error resizing store file", e);
		}
	}

	// ------------- Configuration
	/**
	 * Configuration File
	 * 
	 * <pre>
	 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *       |0|1|2|3|4|5|6|7|8|9|A|B|C|D|E|F|
	 *  +----+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *  |0000|             Salt              |
	 *  +----+---------------+---------------+
	 *  |0010|   Store Size  | prevStoreSize |
	 *  +----+---------------+-------+-------+
	 *  |0020| Est Key Count |  Gen  | Flags |
	 *  +----+-------+-------+-------+-------+
	 *  |0030|   K   |                       |
	 *  +----+-------+-----------------------+
	 *  
	 *  Gen = Generation
	 *    K = K for bloom filter
	 * </pre>
	 */
	private final File configFile;

	/**
	 * Load config file
	 */
	private void loadConfigFile() throws IOException {
		assert cipherManager == null; // never load the configuration twice

		if (!configFile.exists()) {
			// create new
			byte[] newsalt = new byte[0x10];
			random.nextBytes(newsalt);
			cipherManager = new CipherManager(newsalt);

			writeConfigFile();
		} else {
			// try to load
			RandomAccessFile raf = new RandomAccessFile(configFile, "r");
			byte[] salt = new byte[0x10];
			raf.readFully(salt);
			cipherManager = new CipherManager(salt);

			storeSize = raf.readLong();
			prevStoreSize = raf.readLong();
			keyCount.set(raf.readLong());
			generation = raf.readInt();
			flags = raf.readInt();

			if ((flags & FLAG_DIRTY) != 0)
				flags |= FLAG_REBUILD_BLOOM;

			try {
				bloomFilterK = raf.readInt();
			} catch (IOException e) {
				flags |= FLAG_REBUILD_BLOOM;
			}

			raf.close();
		}

	}

	/**
	 * Write config file
	 */
	private void writeConfigFile() {
		configLock.writeLock().lock();
		try {
			File tempConfig = new File(configFile.getPath() + ".tmp");
			RandomAccessFile raf = new RandomAccessFile(tempConfig, "rw");
			raf.seek(0);
			raf.write(cipherManager.getSalt());

			raf.writeLong(storeSize);
			raf.writeLong(prevStoreSize);
			raf.writeLong(keyCount.get());
			raf.writeInt(generation);
			raf.writeInt(flags);
			raf.writeInt(bloomFilterK);
			raf.writeInt(0);
			raf.writeLong(0);

			raf.close();

			FileUtil.renameTo(tempConfig, configFile);
		} catch (IOException ioe) {
			Logger.error(this, "error writing config file for " + name, ioe);
		} finally {
			configLock.writeLock().unlock();
		}
	}

	// ------------- Store resizing
	private long prevStoreSize = 0;
	private Lock cleanerLock = new ReentrantLock(); // local to this datastore
	private Condition cleanerCondition = cleanerLock.newCondition();
	private static Lock cleanerGlobalLock = new ReentrantLock(); // global across all datastore
	private Cleaner cleanerThread;

	private final Entry NOT_MODIFIED = new Entry();

	private interface BatchProcessor {
		// return <code>null</code> to free the entry
		// return NOT_MODIFIED to keep the old entry
		Entry process(Entry entry);
	}

	private class Cleaner extends Thread {
		/**
		 * How often the clean should run
		 */
		private static final int CLEANER_PERIOD = 5 * 60 * 1000; // 5 minutes

		public Cleaner() {
			setName("Store-" + name + "-Cleaner");
			setPriority(MIN_PRIORITY);
			setDaemon(true);
		}

		@Override
		public void run() {
			try {
				Thread.sleep((int)(CLEANER_PERIOD / 2 + CLEANER_PERIOD * Math.random()));
			} catch (InterruptedException e){}

			while (!shutdown) {
				cleanerLock.lock();
				try {
					
					long _prevStoreSize;
					configLock.readLock().lock();
					try {
						_prevStoreSize = prevStoreSize;
					} finally {
						configLock.readLock().unlock();
					}

					if (_prevStoreSize != 0 && cleanerGlobalLock.tryLock()) {
						try {
							resizeStore(_prevStoreSize);
						} finally {
							cleanerGlobalLock.unlock();
						}
					}

					boolean _rebuildBloom;
					configLock.readLock().lock();
					try {
						_rebuildBloom = ((flags & FLAG_REBUILD_BLOOM) != 0);
					} finally {
						configLock.readLock().unlock();
					}
					if (_rebuildBloom && prevStoreSize == 0 && cleanerGlobalLock.tryLock()) {
						try {
							rebuildBloom();
						} finally {
							cleanerGlobalLock.unlock();
						}
					}

					try {
						if (bloomFilter != null)
							bloomFilter.force();
					} catch (Exception e) { // may throw IOException (even if it is not defined)
						Logger.error(this, "Can't force bloom filter", e);
					}
					writeConfigFile();

					try {
						cleanerCondition.await(CLEANER_PERIOD, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						Logger.debug(this, "interrupted", e);
					}
				} finally {
					cleanerLock.unlock();
				}
			}
		}

		private static final int RESIZE_MEMORY_ENTRIES = 256; // temporary memory store size (in # of entries)

		/**
		 * Move old entries to new location and resize store
		 */
		private void resizeStore(long _prevStoreSize) {
			Logger.normal(this, "Starting datastore resize");
			long startTime = System.currentTimeMillis();

			if (storeSize > _prevStoreSize)
				setStoreFileSize(storeSize);

			int optimialK = BloomFilter.optimialK(BLOOM_FILTER_SIZE, storeSize);
			configLock.writeLock().lock();
			try {
				generation++;
				bloomFilter.fork(optimialK);
				keyCount.set(0);
			} finally {
				configLock.writeLock().unlock();
			}

			final List<Entry> oldEntryList = new LinkedList<Entry>();

			// start from end of store, make store shrinking quicker 
			long startOffset = (_prevStoreSize / RESIZE_MEMORY_ENTRIES) * RESIZE_MEMORY_ENTRIES;
			int i = 0;
			for (long curOffset = startOffset; curOffset >= 0; curOffset -= RESIZE_MEMORY_ENTRIES) {
				if (shutdown || _prevStoreSize != prevStoreSize) {
					bloomFilter.discard();
					return;
				}

				batchProcessEntries(curOffset, RESIZE_MEMORY_ENTRIES, new BatchProcessor() {
					public Entry process(Entry entry) {
						if (entry.generation != generation) {
							entry.generation = generation;
							keyCount.incrementAndGet();
						}

						if (entry.storeSize == storeSize) {
							// new size, don't have to relocate
							if (entry.generation != generation) {
								// update filter
								bloomFilter.updateFilter(entry.getDigestedRoutingKey());
								return entry;
							} else {
								return NOT_MODIFIED;
							}
						}

						// remove from store, prepare for relocation
						try {
							entry.setData(readHeader(entry.curOffset), readData(entry.curOffset));
							oldEntryList.add(entry);
						} catch (IOException e) {
							Logger.error(this, "error reading entry (offset=" + entry.curOffset + ")", e);
						}
						return null;
					}
				});

				// shrink data file to current size
				if (storeSize < _prevStoreSize)
					setStoreFileSize(Math.max(storeSize, curOffset));

				// try to resolve the list
				ListIterator<Entry> it = oldEntryList.listIterator();
				while (it.hasNext()) {
					if (resolveOldEntry(it.next()))
						it.remove();
				}

				long processed = _prevStoreSize - curOffset;
				if (i++ % 16 == 0)
					Logger.normal(this, "Store resize (" + name + "): " + processed + "/" + _prevStoreSize);
			}

			long endTime = System.currentTimeMillis();
			Logger.normal(this, "Finish resizing (" + name + ") in " + (endTime - startTime) / 1000 + "s");

			configLock.writeLock().lock();
			try {
				if (_prevStoreSize != prevStoreSize)
					return;
				bloomFilter.merge();
				prevStoreSize = 0;

				flags &= ~FLAG_REBUILD_BLOOM;
				checkBloom = true;
				bloomFilterK = optimialK;
			} finally {
				configLock.writeLock().unlock();
			}
		}

		/**
		 * Rebuild bloom filter
		 */
		private void rebuildBloom() {
			if (bloomFilter == null)
				return;

			Logger.normal(this, "Start rebuilding bloom filter (" + name + ")");
			long startTime = System.currentTimeMillis();
			int optimialK = BloomFilter.optimialK(BLOOM_FILTER_SIZE, storeSize);

			configLock.writeLock().lock();
			try {
				generation++;
				bloomFilter.fork(bloomFilterK);
				keyCount.set(0);
			} finally {
				configLock.writeLock().unlock();
			}

			int i = 0;
			for (long curOffset = 0; curOffset < storeSize; curOffset += RESIZE_MEMORY_ENTRIES) {
				if (shutdown || prevStoreSize != 0) {
					bloomFilter.discard();
					return;
				}
				batchProcessEntries(curOffset, RESIZE_MEMORY_ENTRIES, new BatchProcessor() {
					public Entry process(Entry entry) {
						if (entry.generation != generation) {
							bloomFilter.updateFilter(entry.getDigestedRoutingKey());
							keyCount.incrementAndGet();

							entry.generation = generation;
							return entry;
						}
						return NOT_MODIFIED;
					}
				});

				if (i++ % 16 == 0) {
					Logger.normal(this, "Rebuilding bloom filter (" + name + "): " + curOffset + "/" + storeSize);
					writeConfigFile();
				}
			}

			bloomFilter.merge();
			long endTime = System.currentTimeMillis();
			Logger.normal(this, "Finish rebuilding bloom filter (" + name + ") in " + (endTime - startTime) / 1000
			        + "s");

			configLock.writeLock().lock();
			try {
				flags &= ~FLAG_REBUILD_BLOOM;
				checkBloom = true;
				bloomFilterK = optimialK;
			} finally {
				configLock.writeLock().unlock();
			}
		}

		/**
		 * Read a list of items from store.
		 * 
		 * @param offset
		 *            start offset, must be multiple of {@link FILE_SPLIT}
		 * @param length
		 *            number of items to read, must be multiple of {@link FILE_SPLIT}. If this
		 *            excess store size, read as much as possible.
		 * @param processor
		 *            batch processor
		 * @return <code>true</code> if operation complete successfully; <code>false</code>
		 *         otherwise (e.g. can't acquire locks, node shutting down)
		 */
		private boolean batchProcessEntries(long offset, int length, BatchProcessor processor) {
			boolean[] locked = new boolean[length];
			try {
				// acquire all locks in the region, will unlock in the finally block
				for (int i = 0; i < length; i++) {
					if (lockManager.lockEntry(offset + i))
						locked[i] = true;
					else
						return false;
				}

				long startFileOffset = offset * Entry.METADATA_LENGTH;
				long entriesToRead = length;
				long bufLen = Entry.METADATA_LENGTH * entriesToRead;

				ByteBuffer buf = ByteBuffer.allocate((int) bufLen);
				boolean dirty = false;
				try {
					while (buf.hasRemaining()) {
						int status = metaFC.read(buf, startFileOffset + buf.position());
						if (status == -1)
							break;
					}
				} catch (IOException ioe) {
					if (shutdown)
						return false;
					Logger.error(this, "unexpected IOException", ioe);
				}
				buf.flip();

				try {
					for (int j = 0; !shutdown && buf.limit() > j * Entry.METADATA_LENGTH; j++) {
						buf.position(j * Entry.METADATA_LENGTH);
						if (buf.remaining() < Entry.METADATA_LENGTH) // EOF
							break;

						ByteBuffer enBuf = buf.slice();
						enBuf.limit(Entry.METADATA_LENGTH);

						Entry entry = new Entry(enBuf, null, null);
						entry.curOffset = offset + j;

						if (entry.isFree())
							continue; // not occupied

						Entry newEntry = processor.process(entry);
						if (newEntry == null) {// free the offset
							buf.position(j * Entry.METADATA_LENGTH);
							buf.put(ByteBuffer.allocate(Entry.METADATA_LENGTH));
							keyCount.decrementAndGet();

							dirty = true;
						} else if (newEntry == NOT_MODIFIED) {
						} else {
							// write back
							buf.position(j * Entry.METADATA_LENGTH);
							buf.put(newEntry.toMetaDataBuffer());

							assert newEntry.header == null; // not supported
							assert newEntry.data == null; // not supported

							dirty = true;
						}
					}
				} finally {
					// write back.
					if (dirty) {
						buf.flip();

						try {
							while (buf.hasRemaining()) {
								metaFC.write(buf, startFileOffset + buf.position());
							}
						} catch (IOException ioe) {
							Logger.error(this, "unexpected IOException", ioe);
						}
					}
				}

				return true;
			} finally {
				// unlock
				for (int i = 0; i < length; i++)
					if (locked[i])
						lockManager.unlockEntry(offset + i);
			}
		}

		/**
		 * Put back an old entry to store file
		 * 
		 * @param entry
		 * @return <code>true</code> if the entry have put back successfully.
		 */
		private boolean resolveOldEntry(Entry entry) {
			if (!lockDigestedKey(entry.getDigestedRoutingKey(), false))
				return false;
			try {
				entry.storeSize = storeSize;
				long[] offsets = entry.getOffset();

				// Check for occupied entry with same key
				for (long offset : offsets) {
					try {
						if (!isFree(offset)
						        && Arrays.equals(getDigestedKeyFromOffset(offset), entry.getDigestedRoutingKey())) {
							// do nothing
							return true;
						}
					} catch (IOException e) {
						Logger.debug(this, "IOExcception on resolveOldEntry", e);
					}
				}

				// Check for free entry
				for (long offset : offsets) {
					try {
						if (isFree(offset)) {
							writeEntry(entry, offset);
							bloomFilter.updateFilter(entry.getDigestedRoutingKey());
							keyCount.incrementAndGet();
							return true;
						}
					} catch (IOException e) {
						Logger.debug(this, "IOExcception on resolveOldEntry", e);
					}
				}
				return false;
			} finally {
				unlockDigestedKey(entry.getDigestedRoutingKey(), false);
			}
		}
	}

	public void setMaxKeys(long newStoreSize, boolean shrinkNow) throws IOException {
		Logger.normal(this, "[" + name + "] Resize newStoreSize=" + newStoreSize + ", shinkNow=" + shrinkNow);

		configLock.writeLock().lock();
		try {
			if (newStoreSize == this.storeSize)
				return;

			if (prevStoreSize != 0) {
				Logger.normal(this, "[" + name + "] resize already in progress, ignore resize request");
				return;
			}

			prevStoreSize = storeSize;
			storeSize = newStoreSize;
			writeConfigFile();
		} finally {
			configLock.writeLock().unlock();
		}

		if (cleanerLock.tryLock()) {
			cleanerCondition.signal();
			cleanerLock.unlock();
		}
	}

	// ------------- Locking
	volatile boolean shutdown = false;
	private LockManager lockManager;
	private ReadWriteLock configLock = new ReentrantReadWriteLock();

	/**
	 * Lock all possible offsets of a key. This method would release the locks if any locking
	 * operation failed.
	 * 
	 * @param plainKey
	 * @return <code>true</code> if all the offsets are locked.
	 */
	private boolean lockPlainKey(byte[] plainKey, boolean usePrevStoreSize) {
		return lockDigestedKey(cipherManager.getDigestedKey(plainKey), usePrevStoreSize);
	}

	private void unlockPlainKey(byte[] plainKey, boolean usePrevStoreSize) {
		unlockDigestedKey(cipherManager.getDigestedKey(plainKey), usePrevStoreSize);
	}

	/**
	 * Lock all possible offsets of a key. This method would release the locks if any locking
	 * operation failed.
	 * 
	 * @param digestedKey
	 * @return <code>true</code> if all the offsets are locked.
	 */
	private boolean lockDigestedKey(byte[] digestedKey, boolean usePrevStoreSize) {
		// use a set to prevent duplicated offsets,
		// a sorted set to prevent deadlocks
		SortedSet<Long> offsets = new TreeSet<Long>();
		long[] offsetArray = getOffsetFromDigestedKey(digestedKey, storeSize);
		for (long offset : offsetArray)
			offsets.add(offset);
		if (usePrevStoreSize && prevStoreSize != 0) {
			offsetArray = getOffsetFromDigestedKey(digestedKey, prevStoreSize);
			for (long offset : offsetArray)
				offsets.add(offset);
		}

		Set<Long> locked = new TreeSet<Long>();
		for (long offset : offsets) {
			boolean status = lockManager.lockEntry(offset);
			if (!status)
				break;
			locked.add(offset);
		}

		if (locked.size() == offsets.size()) {
			return true;
		} else {
			// failed, remove the locks
			for (long offset : locked)
				lockManager.unlockEntry(offset);
			return false;
		}
	}

	private void unlockDigestedKey(byte[] digestedKey, boolean usePrevStoreSize) {
		// use a set to prevent duplicated offsets
		SortedSet<Long> offsets = new TreeSet<Long>();
		long[] offsetArray = getOffsetFromDigestedKey(digestedKey, storeSize);
		for (long offset : offsetArray)
			offsets.add(offset);
		if (usePrevStoreSize && prevStoreSize != 0) {
			offsetArray = getOffsetFromDigestedKey(digestedKey, prevStoreSize);
			for (long offset : offsetArray)
				offsets.add(offset);
		}

		for (long offset : offsets) {
			lockManager.unlockEntry(offset);
		}
	}

	public class ShutdownDB implements Runnable {
		public void run() {
			shutdown = true;
			lockManager.shutdown();

			cleanerLock.lock();
			try {
				cleanerCondition.signalAll();
				cleanerThread.interrupt();
			} finally {
				cleanerLock.unlock();
			}

			configLock.writeLock().lock();
			try {
				flushAndClose();
				flags &= ~FLAG_DIRTY; // clean shutdown
				writeConfigFile();
			} finally {
				configLock.writeLock().unlock();
			}
		}
	}

	// ------------- Hashing
	private CipherManager cipherManager;

	/**
	 * Get offset in the hash table, given a plain routing key.
	 * 
	 * @param plainKey
	 * @param storeSize
	 * @return
	 */
	private long[] getOffsetFromPlainKey(byte[] plainKey, long storeSize) {
		return getOffsetFromDigestedKey(cipherManager.getDigestedKey(plainKey), storeSize);
	}

	/**
	 * Get offset in the hash table, given a digested routing key.
	 * 
	 * @param digestedKey
	 * @param storeSize
	 * @return
	 */
	private long[] getOffsetFromDigestedKey(byte[] digestedKey, long storeSize) {
		long keyValue = Fields.bytesToLong(digestedKey);
		long[] offsets = new long[OPTION_MAX_PROBE];

		for (int i = 0; i < OPTION_MAX_PROBE; i++) {
			// h + 141 i^2 + 13 i
			offsets[i] = ((keyValue + 141 * (i * i) + 13 * i) & Long.MAX_VALUE) % storeSize;
		}

		return offsets;
	}

	// ------------- Statistics (a.k.a. lies)
	private AtomicLong hits = new AtomicLong();
	private AtomicLong misses = new AtomicLong();
	private AtomicLong writes = new AtomicLong();
	private AtomicLong keyCount = new AtomicLong();
	private AtomicLong bloomFalsePos = new AtomicLong();

	public long hits() {
		return hits.get();
	}

	public long misses() {
		return misses.get();
	}

	public long writes() {
		return writes.get();
	}

	public long keyCount() {
		return keyCount.get();
	}

	public long getMaxKeys() {
		configLock.readLock().lock();
		long _storeSize = storeSize;
		configLock.readLock().unlock();
		return _storeSize;
	}

	public long getBloomFalsePositive() {
		return bloomFalsePos.get();
	}

	// ------------- Migration
	public void migrationFrom(File storeFile, File keyFile) {
		try {
			System.out.println("Migrating from " + storeFile);

			RandomAccessFile storeRAF = new RandomAccessFile(storeFile, "r");
			RandomAccessFile keyRAF = keyFile.exists() ? new RandomAccessFile(keyFile, "r") : null;

			byte[] header = new byte[headerBlockLength];
			byte[] data = new byte[dataBlockLength];
			byte[] key = new byte[fullKeyLength];

			long maxKey = storeRAF.length() / (headerBlockLength + dataBlockLength);

			for (int l = 0; l < maxKey; l++) {
				if (l % 1024 == 0) {
					System.out.println(" migrating key " + l + "/" + maxKey);
					WrapperManager.signalStarting(10 * 60 * 1000); // max 10 minutes for every 1024 keys  
				}

				boolean keyRead = false;
				storeRAF.readFully(header);
				storeRAF.readFully(data);
				try {
					if (keyRAF != null) {
						keyRAF.readFully(key);
						keyRead = true;
					}
				} catch (IOException e) {
				}

				try {
					StorableBlock b = callback.construct(data, header, null, keyRead ? key : null);
					put(b, b.getRoutingKey(), b.getFullKey(), data, header, true);
				} catch (KeyVerifyException e) {
					System.out.println("kve at block " + l);
				} catch (KeyCollisionException e) {
					System.out.println("kce at block " + l);
				}
			}
		} catch (EOFException eof) {
			// done
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	// XXX Old Format, to be removed in next build
	private final static int FILE_SPLIT = 0x04;
	private final long ENTRY_HEADER_LENGTH = 0x70L;
	private long entryPaddingLength;
	private long entryTotalLength;

	public void migrateFromOldSaltedHash() {
		long length = ENTRY_HEADER_LENGTH + headerBlockLength + dataBlockLength;
		entryPaddingLength = 0x200L - (length % 0x200L);
		entryTotalLength = length + entryPaddingLength;

        DecimalFormat fmt = new DecimalFormat("000");
        int c = 0;
		for (int i = 0; i < FILE_SPLIT; i++) {
			File storeFiles = new File(baseDir, name + ".data-" + fmt.format(i));
			if (!storeFiles.exists())
				continue;
			
			try {
				RandomAccessFile storeRAF = new RandomAccessFile(storeFiles, "rw");
				storeRAF.seek(0);

				byte[] b = new byte[(int) entryTotalLength];

				while (!shutdown) {
					WrapperManager.signalStarting(10 * 60 * 1000); // max 10 minutes
					int status = storeRAF.read(b);
					if (status != entryTotalLength)
						break;
					
					ByteBuffer bf = ByteBuffer.wrap(b);
					Entry e = new Entry();
					e.readOldFormat(bf);
					e.generation = generation;
					
					if (!e.isFree()) {
						if (c++ % 1024 == 0)
							System.out.println(name + ": old salt hash-->new salt hash migrated: " + c + " keys");
						cleanerThread.resolveOldEntry(e);
					}
				}
				
				try {
					storeRAF.close();
				} catch (IOException e) {
				}
			} catch (IOException ioe) {
			}
			if (!shutdown)
				storeFiles.delete();	
		}

		System.out.println(name + ": old salt hash-->new salt hash migrated: " + c + " keys(done)");
	}
}
