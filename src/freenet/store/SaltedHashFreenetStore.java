/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.store;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
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

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.keys.KeyVerifyException;
import freenet.node.SemiOrderedShutdownHook;
import freenet.support.BloomFilter;
import freenet.support.ByteArrayWrapper;
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
	private static final boolean OPTION_SAVE_PLAINKEY = true;
	private static final int OPTION_MAX_PROBE = 4;

	private static final byte FLAG_DIRTY = 0x1;
	private static final byte FLAG_REBUILD_BLOOM = 0x2;
	
	private static final int BLOOM_FILTER_SIZE = 0x8000000; // bits
	private static final int BLOOM_FILTER_K = 5;
	private static final boolean updateBloom = true;
	private static final boolean checkBloom = true;
	private BloomFilter bloomFilter;

	private static final boolean logLOCK = false;
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
	private byte generation;
	private byte flags;

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

		long length = ENTRY_HEADER_LENGTH + headerBlockLength + dataBlockLength;
		entryPaddingLength = 0x200L - (length % 0x200L);
		entryTotalLength = length + entryPaddingLength;

		// Create a directory it not exist
		this.baseDir.mkdirs();

		configFile = new File(this.baseDir, name + ".config");
		loadConfigFile();

		openStoreFiles(baseDir, name);

		if (updateBloom || checkBloom) {
			File bloomFile = new File(this.baseDir, name + ".bloom");
			if (!bloomFile.exists() || bloomFile.length() != BLOOM_FILTER_SIZE / 8)
				flags |= FLAG_REBUILD_BLOOM;
			bloomFilter = new BloomFilter(bloomFile, BLOOM_FILTER_SIZE, BLOOM_FILTER_K);
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
				Entry entry = probeEntry(routingKey);

				if (entry == null) {
					misses.incrementAndGet();
					return null;
				}

				try {
					StorableBlock block = entry.getStorableBlock(routingKey, fullKey);
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
	 * @return <code>Entry</code> object
	 * @throws IOException
	 */
	private Entry probeEntry(byte[] routingKey) throws IOException {
		if (checkBloom)
			if (!bloomFilter.checkFilter(getDigestedRoutingKey(routingKey)))
				return null;

		Entry entry = probeEntry0(routingKey, storeSize);

		if (entry == null && prevStoreSize != 0)
			entry = probeEntry0(routingKey, prevStoreSize);
		if (checkBloom && entry == null)
			bloomFalsePos.incrementAndGet();

		return entry;
	}

	private Entry probeEntry0(byte[] routingKey, long probeStoreSize) throws IOException {
		Entry entry = null;
		long[] offset = getOffsetFromPlainKey(routingKey, probeStoreSize);

		for (int i = 0; i < offset.length; i++) {
			if (logDEBUG)
				Logger.debug(this, "probing for i=" + i + ", offset=" + offset[i]);

			try {
				entry = readEntry(offset[i], routingKey);
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
				// don't use fetch(), as fetch() would do a miss++/hit++
				Entry oldEntry = probeEntry(routingKey);
				if (oldEntry != null) {
					long oldOffset = oldEntry.curOffset;
					try {
						StorableBlock oldBlock = oldEntry.getStorableBlock(routingKey, fullKey);
						if (!collisionPossible)
							return;
						if (block.equals(oldBlock)) {
							return; // already in store
						} else {
							if (!overwrite)
								throw new KeyCollisionException();
						}
					} catch (KeyVerifyException e) {
						// ignore
					}

					// Overwrite old offset
					Entry entry = new Entry(routingKey, header, data);
					writeEntry(entry, oldOffset);
					writes.incrementAndGet();
					if (oldEntry.getGeneration() != generation)
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
							bloomFilter.updateFilter(getDigestedRoutingKey(routingKey));
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
					bloomFilter.updateFilter(getDigestedRoutingKey(routingKey));
				oldEntry = readEntry(offset[0], null);
				writeEntry(entry, offset[0]);
				writes.incrementAndGet();
				if (oldEntry.getGeneration() != generation)
					keyCount.incrementAndGet();
			} finally {
				unlockPlainKey(routingKey, false);
			}
		} finally {
			configLock.readLock().unlock();
		}
	}

	// ------------- Entry I/O

	// split the files for better concurrency
	// you may even some if you have lots of mount points =)
	private final static int FILE_SPLIT = 0x04;
	private File[] storeFiles;
	private RandomAccessFile[] storeRAF;
	private FileChannel[] storeFC;

	/** Flag for occupied space */
	private final long ENTRY_FLAG_OCCUPIED = 0x00000001L;
	/** Flag for plain key available */
	private final long ENTRY_FLAG_PLAINKEY = 0x00000002L;

	private static final long ENTRY_HEADER_LENGTH = 0x70L;
	private final long entryPaddingLength;
	private final long entryTotalLength;

	/**
	 * Data entry
	 * 
	 * <pre>
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
	 *  +----+-+-----------------------------+
	 *  |0060|G|          Reserved           |
	 *  +----+-+-----------------------------+
	 *  |0070|       Encrypted Header        |
	 *  |  . + - - - - - - - - - - - - - - - +
	 *  |  . |        Encrypted Data         |
	 *  +----+-------------------------------+
	 *  |    |           Padding             |
	 *  +----+-------------------------------+
	 * </pre>
	 * 
	 * Total length is padded to multiple of 512bytes. All reserved bytes should be zero when
	 * written, ignored on read.
	 */
	private class Entry {
		private byte[] plainRoutingKey;
		private byte[] digestedRoutingKey;
		private byte[] dataEncryptIV;
		private long flag;
		private long storeSize;
		private byte generation;
		private byte[] header;
		private byte[] data;
		
		private boolean isEncrypted;
		public long curOffset = -1;
		

		/**
		 * Create a new entry
		 *
		 * @param plainRoutingKey
		 * @param header
		 * @param data
		 */
		public Entry(byte[] plainRoutingKey, byte[] header, byte[] data) {
			this.plainRoutingKey = plainRoutingKey;

			flag = ENTRY_FLAG_OCCUPIED;
			storeSize = SaltedHashFreenetStore.this.storeSize;
			generation = SaltedHashFreenetStore.this.generation;

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

		/**
		 * @return the storeSize
		 */
		protected long getStoreSize() {
			return storeSize;
		}

		/**
		 * @param storeSize
		 * 		the storeSize to set
		 */
		protected void setStoreSize(long storeSize) {
			this.storeSize = storeSize;
		}

		public Entry(ByteBuffer in) {
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

		private Entry() {
        }

		public ByteBuffer toByteBuffer() {
			ByteBuffer out = ByteBuffer.allocate((int) entryTotalLength);
			encrypt();
			out.put(getDigestedRoutingKey());
			out.put(dataEncryptIV);

			out.putLong(flag);
			out.putLong(storeSize);

			if (OPTION_SAVE_PLAINKEY && plainRoutingKey != null) {
				out.put(plainRoutingKey);
			}
			
			out.position(0x60);
			out.put(generation);
			
			// reserved bytes
			out.position((int) ENTRY_HEADER_LENGTH);

			out.put(header);
			out.put(data);

			assert out.remaining() == entryPaddingLength;
			out.position(0);

			return out;
		}

		public StorableBlock getStorableBlock(byte[] routingKey, byte[] fullKey) throws KeyVerifyException {
			if ((flag & ENTRY_FLAG_OCCUPIED) == 0)
				return null; // this is a free block
			if (!decrypt(routingKey))
				return null;

			StorableBlock block = callback.construct(data, header, routingKey, fullKey);
			byte[] blockRoutingKey = block.getRoutingKey();

			if (!Arrays.equals(blockRoutingKey, routingKey)) {
				// either the data is corrupted or we have found a SHA-1 collision
				// can't recover, as decrypt() depends on a correct route key
				return null;
			}

			return block;
		}

		public long[] getOffset() {
			if (digestedRoutingKey != null)
				return getOffsetFromDigestedKey(digestedRoutingKey, storeSize);
			else
				return getOffsetFromPlainKey(plainRoutingKey, storeSize);
		}

		/**
		 * Verify and decrypt this entry
		 *
		 * @param routingKey
		 * @return <code>true</code> if the <code>routeKey</code> match and the entry is decrypted.
		 */
		private boolean decrypt(byte[] routingKey) {
			if (!isEncrypted) {
				// Already decrypted
				if (Arrays.equals(this.plainRoutingKey, routingKey))
					return true;
				else
					return false;
			}

			if (plainRoutingKey != null) {
				// we knew the key
				if (!Arrays.equals(this.plainRoutingKey, routingKey)) {
					return false;
				}
			} else {
				// we do not know the plain key, let's check the digest
				if (!Arrays.equals(this.digestedRoutingKey, SaltedHashFreenetStore.this
				        .getDigestedRoutingKey(routingKey)))
					return false;
			}

			this.plainRoutingKey = routingKey;

			PCFBMode cipher = makeCipher(plainRoutingKey);
			header = cipher.blockDecipher(header, 0, header.length);
			data = cipher.blockDecipher(data, 0, data.length);

			isEncrypted = false;

			return true;
		}

		/**
		 * Encrypt this entry
		 */
		private void encrypt() {
			if (isEncrypted)
				return;

			dataEncryptIV = new byte[16];
			random.nextBytes(dataEncryptIV);

			PCFBMode cipher = makeCipher(plainRoutingKey);
			header = cipher.blockEncipher(header, 0, header.length);
			data = cipher.blockEncipher(data, 0, data.length);

			getDigestedRoutingKey();
			isEncrypted = true;
		}

		/**
		 * Create Cipher
		 */
		private PCFBMode makeCipher(byte[] routingKey) {
			byte[] iv = new byte[0x20]; // 256 bits

			System.arraycopy(salt, 0, iv, 0, 0x10);
			System.arraycopy(dataEncryptIV, 0, iv, 0x10, 0x10);

			try {
				BlockCipher aes = new Rijndael(256, 256);
				aes.initialize(routingKey);

				return PCFBMode.create(aes, iv);
			} catch (UnsupportedCipherException e) {
				Logger.error(this, "Rijndael not supported!", e);
				throw new RuntimeException(e);
			}
		}

		public boolean isFree() {
			return (flag & ENTRY_FLAG_OCCUPIED) == 0;
		}

		public byte[] getDigestedRoutingKey() {
			if (digestedRoutingKey == null)
				digestedRoutingKey = SaltedHashFreenetStore.this.getDigestedRoutingKey(this.plainRoutingKey);
			return digestedRoutingKey;
		}

		public byte getGeneration() {
			return generation;
		}

		public void setGeneration(byte generation) {
			this.generation = generation;
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
		storeFiles = new File[FILE_SPLIT];
		storeRAF = new RandomAccessFile[FILE_SPLIT];
		storeFC = new FileChannel[FILE_SPLIT];

		DecimalFormat fmt = new DecimalFormat("000");
		for (int i = 0; i < FILE_SPLIT; i++) {
			storeFiles[i] = new File(baseDir, name + ".data-" + fmt.format(i));

			storeRAF[i] = new RandomAccessFile(storeFiles[i], "rw");

			storeFC[i] = storeRAF[i].getChannel();
			storeFC[i].lock();
		}

		long storeFileSize = Math.max(storeSize, prevStoreSize);
		setStoreFileSize(storeFileSize);
	}

	/**
	 * Flush all store files to disk
	 */
	private void flushStoreFiles() {
		for (int i = 0; i < FILE_SPLIT; i++) {
			try {
				storeFC[i].force(true);
			} catch (Exception e) {
				Logger.normal(this, "error flushing store file", e);
			}
		}
	}

	/**
	 * Read entry from disk.
	 *
	 * Before calling this function, you should acquire all required locks.
	 */
	private Entry readEntry(long offset, byte[] routingKey) throws IOException {
		int split = (int) (offset % FILE_SPLIT);
		long rawOffset = (offset / FILE_SPLIT) * entryTotalLength;

		ByteBuffer bf = ByteBuffer.allocate((int) entryTotalLength);
		do {
			int status = storeFC[split].read(bf, rawOffset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());
		bf.flip();

		Entry entry = new Entry(bf);

		if (routingKey != null) {
			boolean decrypted = entry.decrypt(routingKey);
			if (!decrypted)
				return null;
		}

		entry.curOffset = offset;
		return entry;
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
		entry.encrypt();

		int split = (int) (offset % FILE_SPLIT);
		long rawOffset = (offset / FILE_SPLIT) * entryTotalLength;

		ByteBuffer bf = entry.toByteBuffer();
		do {
			int status = storeFC[split].write(bf, rawOffset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());

		entry.curOffset = offset;
	}

	/**
	 * Free an entry by zeroing the header
	 *
	 * @param offset
	 * @throws IOException
	 */
	private void freeOffset(long offset) throws IOException {
		int split = (int) (offset % FILE_SPLIT);
		long rawOffset = (offset / FILE_SPLIT) * entryTotalLength;

		ByteBuffer bf = ByteBuffer.allocate(0x200); // 512 bytes, one physical disk block
		do {
			int status = storeFC[split].write(bf, rawOffset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());
	}

	/**
	 * Get store size
	 */
	private long getStoreSize(long offset) throws IOException {
		int split = (int) (offset % FILE_SPLIT);
		long rawOffset = (offset / FILE_SPLIT) * entryTotalLength + 0x38;

		ByteBuffer bf = ByteBuffer.allocate(0x8);

		do {
			int status = storeFC[split].read(bf, rawOffset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());

		return bf.getLong(0);
	}

	/**
	 * Check if a block is free
	 *
	 * @param offset
	 * @throws IOException
	 */
	private boolean isFree(long offset) throws IOException {
		int split = (int) (offset % FILE_SPLIT);
		long rawOffset = (offset / FILE_SPLIT) * entryTotalLength + 0x30;

		ByteBuffer bf = ByteBuffer.allocate(0x8);

		do {
			int status = storeFC[split].read(bf, rawOffset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());

		return ((bf.getLong(0) & ENTRY_FLAG_OCCUPIED) == 0);
	}
	
	private byte[] getDigestedKeyFromOffset(long offset) throws IOException {
		int split = (int) (offset % FILE_SPLIT);
		long rawOffset = (offset / FILE_SPLIT) * entryTotalLength;

		ByteBuffer bf = ByteBuffer.wrap(new byte[0x20]);

		do {
			int status = storeFC[split].read(bf, rawOffset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());

		return bf.array();
	}

	private void flushAndClose() {
		for (int i = 0; i < FILE_SPLIT; i++) {
			try {
				storeFC[i].force(true);
				storeFC[i].close();
			} catch (Exception e) {
				Logger.error(this, "error flusing store", e);
			}
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
		for (int i = 0; i < FILE_SPLIT; i++) {
			try {
				storeRAF[i].setLength(entryTotalLength * (storeFileSize / FILE_SPLIT + 1));
			} catch (IOException e) {
				Logger.error(this, "error resizing store file", e);
			}
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
	 *  +----+---------------+-+-+-----------+
	 *  |0020| Est Key Count |G|F|  reserved |
	 *  +----+---------------+-+-+-----------+
	 *  
	 *  G = Generation
	 *  F = Flags
	 * </pre>
	 */
	private final File configFile;

	/**
	 * Load config file
	 */
	private void loadConfigFile() throws IOException {
		assert salt == null; // never load the configuration twice

		if (!configFile.exists()) {
			// create new
			salt = new byte[0x10];
			random.nextBytes(salt);

			writeConfigFile();
		} else {
			// try to load
			RandomAccessFile raf = new RandomAccessFile(configFile, "r");
			salt = new byte[0x10];
			raf.readFully(salt);

			storeSize = raf.readLong();
			prevStoreSize = raf.readLong();
			keyCount.set(raf.readLong());
			generation = raf.readByte();
			flags = raf.readByte();
			
			if ((flags & FLAG_DIRTY) != 0)
				flags |= FLAG_REBUILD_BLOOM;

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
			raf.write(salt);

			raf.writeLong(storeSize);
			raf.writeLong(prevStoreSize);
			raf.writeLong(keyCount.get());
			raf.write(generation);
			raf.write(flags);
			raf.setLength(0x30);

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
		private static final int CLEANER_PERIOD = 10 * 60 * 1000; // 10 minutes

		public Cleaner() {
			setName("Store-" + name + "-Cleaner");
			setPriority(MIN_PRIORITY);
			setDaemon(true);
		}

		@Override
		public void run() {
			while (!shutdown) {
				cleanerLock.lock();
				try {
					long _prevStoreSize;
					boolean _rebuildBloom;
					
					configLock.readLock().lock();
					try {
						_prevStoreSize = prevStoreSize;
						_rebuildBloom = ((flags & FLAG_REBUILD_BLOOM) != 0);
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
		private static final int RESIZE_DISK_ENTRIES = 8192; // temporary disk store size (in # of entries)

		/**
		 * Move old entries to new location and resize store
		 */
		private void resizeStore(long _prevStoreSize) {
			Logger.normal(this, "Starting datastore resize");
			long startTime = System.currentTimeMillis();

			if (storeSize > _prevStoreSize)
				setStoreFileSize(storeSize);

			initOldEntriesFile();

			final List<Entry> oldEntryList = new LinkedList<Entry>();

			// start from end of store, make store shrinking quicker 
			long startOffset = (_prevStoreSize / RESIZE_MEMORY_ENTRIES) * RESIZE_MEMORY_ENTRIES;
			int i = 0;
			for (long curOffset = startOffset; curOffset >= 0; curOffset -= RESIZE_MEMORY_ENTRIES) {
				if (shutdown || _prevStoreSize != prevStoreSize)
					return;

				batchProcessEntries(curOffset, RESIZE_MEMORY_ENTRIES, new BatchProcessor() {
					public Entry process(Entry entry) {
						if (entry.getStoreSize() == storeSize) // new size
							return NOT_MODIFIED;

						oldEntryList.add(entry);
						return null;
					}
				});

				if (storeSize < _prevStoreSize)
					setStoreFileSize(Math.max(storeSize, curOffset));

				// try to resolve the list
				ListIterator<Entry> it = oldEntryList.listIterator();
				while (it.hasNext()) {
					if (resolveOldEntry(it.next()))
						it.remove();
				}

				// write unresolved entry to file
				it = oldEntryList.listIterator();
				while (it.hasNext()) {
					rrWriteOldEntry(it.next());
					it.remove();
				}
				
				long processed = _prevStoreSize - curOffset;
				if (i++ % 16 == 0)
					Logger.normal(this, "Store resize (" + name + "): " + processed + "/" + _prevStoreSize);
			}

			resolveOldEntriesFile();

			long endTime = System.currentTimeMillis();
			Logger.normal(this, "Finish resizing (" + name + ") in " + (endTime - startTime) / 1000 + "s");
			
			configLock.writeLock().lock();
			try {
				if (_prevStoreSize != prevStoreSize)
					return;
				prevStoreSize = 0;
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
			
			configLock.writeLock().lock();
			try {
				generation++;
				bloomFilter.fork();
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
						if (entry.getGeneration() != generation) {
							bloomFilter.updateFilter(entry.getDigestedRoutingKey());
							keyCount.incrementAndGet();
							
							entry.setGeneration(generation);
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
			assert offset % FILE_SPLIT == 0;
			assert length % FILE_SPLIT == 0;

			boolean[] locked = new boolean[length];
			try {
				// acquire all locks in the region, will unlock in the finally block
				for (int i = 0; i < length; i++) {
					if (lockEntry(offset + i))
						locked[i] = true;
					else
						return false;
				}

				long startFileOffset = (offset / FILE_SPLIT) * entryTotalLength;
				long entriesToRead = length / FILE_SPLIT;
				long bufLen = entryTotalLength * entriesToRead;

				ByteBuffer buf = ByteBuffer.allocate((int) bufLen);
				for (int i = 0; i < FILE_SPLIT; i++) { // for each split file
					boolean dirty = false;
					buf.clear();
					try {
						while (buf.hasRemaining()) {
							int status = storeFC[i].read(buf, startFileOffset + buf.position());
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
						for (int j = 0; buf.limit() >= j * entryTotalLength; j++) {
							if (shutdown)
								return false;

							buf.position((int) (j * entryTotalLength));
							if (buf.remaining() < entryTotalLength) // EOF
								break;

							ByteBuffer enBuf = buf.slice();
							enBuf.limit((int) entryTotalLength);

							Entry entry = new Entry(enBuf);
							entry.curOffset = offset + j * FILE_SPLIT + i;

							if (entry.isFree())
								continue; // not occupied

							Entry newEntry = processor.process(entry);
							if (newEntry == null) {// free the offset
								buf.position((int) (j * entryTotalLength));
								buf.put(ByteBuffer.allocate((int) entryTotalLength));
								keyCount.decrementAndGet();

								dirty = true;
							} else if (newEntry == NOT_MODIFIED) {
							} else {
								// write back
								buf.position((int) (j * entryTotalLength));
								buf.put(newEntry.toByteBuffer());
								dirty = true;
							} 
						}
					} finally {
						// write back.
						if (dirty) {
							buf.flip();

							try {
								while (buf.hasRemaining()) {
									storeFC[i].write(buf, startFileOffset + buf.position());
								}
							} catch (IOException ioe) {
								Logger.error(this, "unexpected IOException", ioe);
							}
						}
					}
				}

				return true;
			} finally {
				// unlock
				for (int i = 0; i < length; i++)
					if (locked[i])
						unlockEntry(offset + i);
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
				entry.setStoreSize(storeSize);
				long[] offsets = entry.getOffset();

				// Check for occupied entry with same key
				for (long offset : offsets) {
					try {
						if (!isFree(offset)
								&& Arrays.equals(getDigestedKeyFromOffset(offset), entry.getDigestedRoutingKey())) {
							writeEntry(entry, offset);	// overwrite, don't update key count
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

		private File oldEntriesFile; // round-ribbon
		private RandomAccessFile oldEntriesRAF;
		private long oldEntriesFileOffset;

		private void initOldEntriesFile() {
			try {
				oldEntriesFile = new File(baseDir, name + ".oldEntries");
				oldEntriesRAF = new RandomAccessFile(oldEntriesFile, "rw");
				oldEntriesRAF.setLength(RESIZE_DISK_ENTRIES * entryTotalLength);
				oldEntriesFileOffset = 0;
			} catch (IOException ioe) {
				Logger.error(this, "Cannot create oldEntries file for resize, will use memory only", ioe);
			}
		}

		private void resolveOldEntriesFile() {
			if (oldEntriesRAF == null)
				return;

			for (int offset = 0; offset < RESIZE_DISK_ENTRIES; offset++) {
				Entry oldEntry = readOldEntry(offset);
				if (oldEntry != null && !oldEntry.isFree()) // the current position already in use
					resolveOldEntry(oldEntry);
			}
			try {
				oldEntriesRAF.close();
			} catch (IOException ioe) {
				// ignore
			}
			oldEntriesFile.delete();
		}

		private void rrWriteOldEntry(Entry entry) {
			if (oldEntriesRAF == null)
				return;

			long offset = oldEntriesFileOffset++ % RESIZE_DISK_ENTRIES;
			Entry rrOldEntry = readOldEntry(offset);
			if (rrOldEntry != null && !rrOldEntry.isFree()) // the current position already in use
				resolveOldEntry(rrOldEntry);

			byte[] buf = new byte[(int) entryTotalLength];
			entry.toByteBuffer().get(buf);
			try {
				oldEntriesRAF.seek(offset * entryTotalLength);
				oldEntriesRAF.write(buf);
			} catch (IOException e) {
				Logger.debug(this, "IOException on rrWriteOldEntry", e);
			}
		}

		private Entry readOldEntry(long offset) {
			if (oldEntriesRAF == null)
				return null;

			try { 
				byte[] buf = new byte[(int) entryTotalLength];
				oldEntriesRAF.seek(offset * entryTotalLength);
				oldEntriesRAF.readFully(buf);

				return new Entry(ByteBuffer.wrap(buf));
			} catch (IOException e) {
				Logger.debug(this, "IOException on readOldEntry", e);
				return null;
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
			flags |= FLAG_REBUILD_BLOOM;
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
	private boolean shutdown = false;
	private ReadWriteLock configLock = new ReentrantReadWriteLock(); 
	private Lock entryLock = new ReentrantLock();
	private Map<Long, Condition> lockMap = new HashMap<Long, Condition> ();

	/**
	 * Lock the entry
	 * 
	 * This lock is <strong>not</strong> re-entrance. No threads except Cleaner should hold more
	 * then one lock at a time (or deadlock may occur).
	 */
	private boolean lockEntry(long offset) {
		if (logDEBUG && logLOCK)
			Logger.debug(this, "try locking " + offset, new Exception());

		try {
			entryLock.lock();
			try {
				do {
					if (shutdown)
						return false;

					Condition lockCond = lockMap.get(offset);
					if (lockCond != null)
						lockCond.await(10, TimeUnit.SECONDS); // 10s for checking shutdown
					else
						break;
				} while (true);
				lockMap.put(offset, entryLock.newCondition());
			} finally {
				entryLock.unlock();
			}
		} catch (InterruptedException e) {
			Logger.error(this, "lock interrupted", e);
			return false;
		}

		if (logDEBUG && logLOCK)
			Logger.debug(this, "locked " + offset, new Exception());
		return true;
	}

	/**
	 * Unlock the entry
	 */
	private void unlockEntry(long offset) {
		if (logDEBUG && logLOCK)
			Logger.debug(this, "unlocking " + offset, new Exception("debug"));

		entryLock.lock();
		try {
			Condition cond = lockMap.remove(offset);
			assert cond != null;
			cond.signal();
		} finally {
			entryLock.unlock();
		}
	}

	/**
	 * Lock all possible offsets of a key. This method would release the locks if any locking
	 * operation failed.
	 * 
	 * @param plainKey
	 * @return <code>true</code> if all the offsets are locked.
	 */
	private boolean lockPlainKey(byte[] plainKey, boolean usePrevStoreSize) {
		return lockDigestedKey(getDigestedRoutingKey(plainKey), usePrevStoreSize);
	}

	private void unlockPlainKey(byte[] plainKey, boolean usePrevStoreSize) {
		unlockDigestedKey(getDigestedRoutingKey(plainKey), usePrevStoreSize);
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
			boolean status = lockEntry(offset);
			if (!status)
				break;
			locked.add(offset);
		}

		if (locked.size() == offsets.size()) {
			return true;
		} else {
			// failed, remove the locks
			for (long offset : locked)
				unlockEntry(offset);
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
			unlockEntry(offset);
		}
	}

	public class ShutdownDB implements Runnable {
		public void run() {
			shutdown = true;

			synchronized (cleanerLock) {
				cleanerLock.notifyAll();
				cleanerThread.interrupt();
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
	/**
	 * <tt>0x10</tt> bytes of salt for better digestion, not too salty.
	 */
	private byte[] salt;

	private Map<ByteArrayWrapper, byte[]> digestRoutingKeyCache = new LinkedHashMap<ByteArrayWrapper, byte[]>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<ByteArrayWrapper, byte[]> eldest) {
			return size() > 128;
		}
	};
	
	/**
	 * Get hashed routing key
	 *
	 * @param routingKey
	 * @return
	 */
	private byte[] getDigestedRoutingKey(byte[] routingKey) {
		ByteArrayWrapper key = new ByteArrayWrapper(routingKey);
		synchronized (digestRoutingKeyCache) {
			byte[] dk = digestRoutingKeyCache.get(key);
			if (dk != null)
				return dk;
		}
		
		MessageDigest digest = SHA256.getMessageDigest();
		try {
			digest.update(routingKey);
			digest.update(salt);

			byte[] hashedRoutingKey = digest.digest();
			assert hashedRoutingKey.length == 0x20;

			synchronized (digestRoutingKeyCache) {
				digestRoutingKeyCache.put(key, hashedRoutingKey);
			}
			
			return hashedRoutingKey;
		} finally {
			SHA256.returnMessageDigest(digest);
		}
	}

	/**
	 * Get offset in the hash table, given a plain routing key.
	 *
	 * @param plainKey
	 * @param storeSize
	 * @return
	 */
	public long[] getOffsetFromPlainKey(byte[] plainKey, long storeSize) {
		return getOffsetFromDigestedKey(getDigestedRoutingKey(plainKey), storeSize);
	}

	/**
	 * Get offset in the hash table, given a digested routing key.
	 *
	 * @param digestedKey
	 * @param storeSize
	 * @return
	 */
	public long[] getOffsetFromDigestedKey(byte[] digestedKey, long storeSize) {
		long keyValue = Fields.bytesToLong(digestedKey);
		long[] offsets = new long[OPTION_MAX_PROBE];

		for (int i = 0 ; i < OPTION_MAX_PROBE ; i++) {
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
}
