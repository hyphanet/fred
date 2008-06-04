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
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.keys.KeyVerifyException;
import freenet.node.SemiOrderedShutdownHook;
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
	private static final boolean OPTION_SAVE_PLAINKEY = true;
	private static final int OPTION_MAX_PROBE = 4;

	private static final boolean updateBloom = true;
	private static final boolean checkBloom = true;
	private boolean syncBloom = true;
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

		if (updateBloom || checkBloom)
			bloomFilter = new BloomFilter(new File(this.baseDir, name + ".bloom"), 0x4000000, 4);

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
			Entry entry = probeEntry(routingKey);

			if (entry == null) {
				misses.incrementAndGet();
				return null;
			}

			unlockEntry(entry.curOffset);

			try {
				StorableBlock block = entry.getStorableBlock(routingKey, fullKey);
				hits.incrementAndGet();
				if (updateBloom && !checkBloom)
					bloomFilter.updateFilter(getDigestedRoutingKey(routingKey), false);
				return block;
			} catch (KeyVerifyException e) {
				Logger.minor(this, "key verification exception", e);
				misses.incrementAndGet();
				return null;
			}
		} finally {
			configLock.readLock().unlock();
		}
	}

	/**
	 * Find and lock an entry with a specific routing key. <strong>You have to unlock the entry
	 * explicitly yourself!</strong>
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

			if (!lockEntry(offset[i])) {
				Logger.error(this, "can't lock entry: " + offset[i]);
				continue;
			}
			try {
				entry = readEntry(offset[i], routingKey);
				if (entry != null)
					return entry;
			} catch (EOFException e) {
				// may occur on resize, silent it a bit
				Logger.error(this, "EOFException on probeEntry", e);
				continue;
			} finally {
				if (entry == null)
					unlockEntry(offset[i]);
			}
		}
		return null;
	}

	public void put(StorableBlock block, byte[] routingKey, byte[] fullKey, byte[] data, byte[] header,
	        boolean overwrite) throws IOException, KeyCollisionException {
		if (logMINOR)
			Logger.minor(this, "Putting " + HexUtil.bytesToHex(routingKey) + " for " + callback);

		configLock.readLock().lock();
		try {
			// don't use fetch(), as fetch() would do a miss++/hit++
			Entry oldEntry = probeEntry(routingKey);
			if (oldEntry != null) {
				long oldOffset = oldEntry.curOffset;
				try {
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
					if (updateBloom)
						bloomFilter.updateFilter(getDigestedRoutingKey(routingKey), syncBloom);
					Entry entry = new Entry(routingKey, header, data);
					writeEntry(entry, oldOffset);
					writes.incrementAndGet();
					return;
				} finally {
					unlockEntry(oldOffset);
				}
			}

			Entry entry = new Entry(routingKey, header, data);
			long[] offset = entry.getOffset();

			for (int i = 0; i < offset.length; i++) {
				if (!lockEntry(offset[i])) {
					Logger.error(this, "can't lock entry: " + offset[i]);
					return;
				}
				try {
					if (isFree(offset[i])) {
						// write to free block
						if (logDEBUG)
							Logger.debug(this, "probing, write to i=" + i + ", offset=" + offset[i]);
						if (updateBloom)
							bloomFilter.updateFilter(getDigestedRoutingKey(routingKey), syncBloom);
						writeEntry(entry, offset[i]);
						writes.incrementAndGet();
						keyCount.incrementAndGet();
						return;
					}
				} finally {
					unlockEntry(offset[i]);
				}
			}

			// no free blocks, overwrite the first one
			if (!lockEntry(offset[0])) {
				Logger.error(this, "can't lock entry: " + offset[0]);
				return;
			}
			try {
				if (logDEBUG)
					Logger.debug(this, "collision, write to i=0, offset=" + offset[0]);
				if (updateBloom)
					bloomFilter.updateFilter(getDigestedRoutingKey(routingKey), syncBloom);
				writeEntry(entry, offset[0]);
				writes.incrementAndGet();
			} finally {
				unlockEntry(offset[0]);
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
	 *  +----+-------------------------------+
	 *  |0060|            Reserved           |
	 *  +----+-------------------------------+
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

			// reserved bytes
			in.position((int) ENTRY_HEADER_LENGTH);

			header = new byte[headerBlockLength];
			in.get(header);

			data = new byte[dataBlockLength];
			in.get(data);

			assert in.remaining() == entryPaddingLength;

			isEncrypted = true;
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

	private void flushAndClose() {
		for (int i = 0; i < FILE_SPLIT; i++) {
			try {
				storeFC[i].force(true);
				storeFC[i].close();
			} catch (Exception e) {
				Logger.error(this, "error flusing store", e);
			}
		}
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
	 *  +----+---------------+---------------+
	 *  |0010| Est Key Count |    reserved   |
	 *  +----+---------------+---------------+
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
			raf.readLong();

			raf.close();
		}

	}

	/**
	 * Write config file
	 */
	private void writeConfigFile() throws IOException {
		configLock.writeLock().lock();
		try {
			File tempConfig = new File(configFile.getPath() + ".tmp");
			RandomAccessFile raf = new RandomAccessFile(tempConfig, "rw");
			raf.seek(0);
			raf.write(salt);

			raf.writeLong(storeSize);
			raf.writeLong(prevStoreSize);
			raf.writeLong(keyCount.get());
			raf.writeLong(0);

			raf.close();

			FileUtil.renameTo(tempConfig, configFile);
		} finally {
			configLock.writeLock().unlock();
		}
	}

	// ------------- Store resizing
	private long prevStoreSize = 0;
	private static Object cleanerLock = new Object();
	private Cleaner cleanerThread;

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
				synchronized (cleanerLock) {
					configLock.readLock().lock();
					try {
						if (prevStoreSize != 0)
							resizeStore();
					} finally {
						configLock.readLock().unlock();
					}

					cleanerLock.notifyAll();
					try {
						cleanerLock.wait(CLEANER_PERIOD);
					} catch (InterruptedException e) {
						Logger.debug(this, "interrupted", e);
					}
				}
			}
		}

		/**
		 * Phase 1 Rounds
		 */
		private static final int RESIZE_PHASE1_ROUND = 8;
		/**
		 * Maximum resize round
		 */
		private static final int RESIZE_MAX_ROUND = 16;

		/**
		 * Are we shrinking the store?
		 */
		private boolean shrinking;
		private long newEntries;
		private long oldEntries;
		private long freeEntries;
		private long resolvedEntries;
		private long droppedEntries;
		private long maxOldItemOffset;

		/**
		 * Numbers of round resize have ran
		 */
		private int resizeRound;

		/**
		 * Move old entries to new location and resize store
		 */
		private void resizeStore() {
			++resizeRound;
			Logger.normal(this, "Starting datastore resize (round " + resizeRound + ")");

			if (resizeRound == 1) { // first round
				if (storeSize < prevStoreSize) {
					shrinking = true;
				} else {
					setStoreFileSize(storeSize);
				}
				maxOldItemOffset = prevStoreSize - 1;
			}

			boolean needQueue = false;
			if (resizeRound > RESIZE_PHASE1_ROUND) // too many rounds
				needQueue = true;
			if (resizeRound > 1 && droppedEntries == 0 && resolvedEntries == 0) // no progress
				needQueue = true;
			moveOldEntry0(needQueue);

			if (logMINOR)
				Logger.minor(this, "Finished resize round " + resizeRound + ": newEntries=" + newEntries
				        + ", oldEntries=" + oldEntries + ", freeEntries=" + freeEntries + ", resolvedEntries="
				        + resolvedEntries + ", droppedEntries=" + droppedEntries);

			if (shutdown)
				return;

			// Shrink store file size
			if (shrinking)
				setStoreFileSize(Math.max(storeSize, maxOldItemOffset + 1));

			// Check finished
			if (resizeRound >= RESIZE_MAX_ROUND || oldEntries == 0 || resolvedEntries + droppedEntries >= oldEntries) {
				// Finished
				Logger.normal(this, "Datastore resize finished (total " + resizeRound + "rounds)");

				prevStoreSize = 0;
				resizeRound = 0;
			}
		}

		/**
		 * Scan all entries and try to move them
		 */
		private void moveOldEntry0(boolean queueItem) {
			newEntries = 0;
			oldEntries = 0;
			freeEntries = 0;
			resolvedEntries = 0;
			droppedEntries = 0;

			File oldItemFile = null;
			RandomAccessFile oldItemsRAF = null;
			FileChannel oldItemsFC = null;
			if (queueItem) {
				try {
					oldItemFile = new File(baseDir, name + ".oldItems");
					oldItemsRAF = new RandomAccessFile(oldItemFile, "rw");
					oldItemsRAF.seek((oldItemsRAF.length() / entryTotalLength) * entryTotalLength);
					oldItemsFC = oldItemsRAF.getChannel();
				} catch (IOException e) {
				}
			}

			long maxOffset = maxOldItemOffset;
			maxOldItemOffset = 0;
			LOOP_ENTRIES: for (long offset = 0; offset <= maxOffset; offset++) {
				if (logDEBUG && offset % 1024 == 0) {
					Logger.debug(this, "Resize progress: newEntries=" + newEntries + ", oldEntries=" + oldEntries
					        + ", freeEntries=" + freeEntries + ", resolvedEntries=" + resolvedEntries
					        + ", droppedEntries=" + droppedEntries);
				}

				if (shutdown)
					return;

				if (!lockEntry(offset)) //lock
					continue LOOP_ENTRIES;
				try {
					if (isFree(offset)) {
						// free block
						freeEntries++;
						continue LOOP_ENTRIES;
					}

					if (getStoreSize(offset) == storeSize) {
						// new store size entries
						maxOldItemOffset = offset;
						newEntries++;
						continue LOOP_ENTRIES;
					}

					// if (entry.getStoreSize() == prevStoreSize)
					// old store size entries, try to move them
					maxOldItemOffset = offset;
					oldEntries++;

					Entry entry = readEntry(offset, null);
					entry.setStoreSize(storeSize);
					long[] newOffset = entry.getOffset();

					// Check if I can keep my current offset
					for (int i = 0; i < newOffset.length; i++) {
						if (newOffset[i] == offset) { // lucky!
							writeEntry(entry, offset); // write back entry storeSize
							resolvedEntries++;

							if (logDEBUG)
								Logger.debug(this, "old entry " + HexUtil.bytesToHex(entry.getDigestedRoutingKey())
								        + " resolved without moving");

							continue LOOP_ENTRIES;
						}
					}

					boolean[] locked = new boolean[newOffset.length];
					try {
						// Lock all possible slots first
						for (int i = 0; i < newOffset.length; i++) {
							if (lockEntry(newOffset[i])) { // lock
								locked[i] = true;
							} else if (shutdown) { // oops
								return;
							}
						}

						// Probe for a free slot
						for (int i = 0; i < newOffset.length; i++) {
							// see what's in the new offset
							Entry newOffsetEntry = readEntry(newOffset[i], null);

							// Free slot
							if (newOffsetEntry.isFree()) {
								// the new offset is freeeeeeee..
								writeEntry(entry, newOffset[i]);
								freeOffset(offset);
								resolvedEntries++;

								if (logDEBUG)
									Logger.debug(this, "old entry " + HexUtil.bytesToHex(entry.getDigestedRoutingKey())
									        + " resolved by moving to free block");

								continue LOOP_ENTRIES;
							}

							// Same digested key: same routing key or SHA-256 collision
							byte[] digestedRoutingKey = entry.getDigestedRoutingKey();
							byte[] digestedRoutingKey2 = newOffsetEntry.getDigestedRoutingKey();
							if (Arrays.equals(digestedRoutingKey, digestedRoutingKey2)) {
								// assume same routing key, drop this as duplicate
								freeOffset(offset);
								keyCount.decrementAndGet();
								droppedEntries++;

								if (logDEBUG)
									Logger.debug(this, "old entry " + HexUtil.bytesToHex(entry.getDigestedRoutingKey())
									        + " dropped duplicate");

								continue LOOP_ENTRIES;
							}
						}

						if (queueItem && oldItemsFC.position() < 0x10000000) { // Limit to 256MiB
							if (logDEBUG)
								Logger.debug(this, "old entry " + HexUtil.bytesToHex(entry.getDigestedRoutingKey())
								        + " queued");
							writeOldItem(oldItemsFC, entry);
							freeOffset(offset);
							keyCount.decrementAndGet();
						}
					} finally {
						// unlock all entries
						for (int i = 0; i < newOffset.length; i++) {
							if (locked[i]) {
								unlockEntry(newOffset[i]);
							}
						}
					}
				} catch (IOException e) {
					Logger.debug(this, "IOExcception on moveOldEntries0", e);
				} finally {
					unlockEntry(offset);
				}
			}

			if (queueItem) {
				try {
					oldItemsRAF.seek(0);
					putBackOldItems(oldItemsFC);
				} catch (IOException e) {
				} finally {
					try {
						oldItemsRAF.close();
						oldItemFile.delete();
					} catch (IOException e2) {
					}
				}
			}
		}

		/**
		 * Put back oldItems with best effort
		 *
		 * @throws IOException
		 */
		private void putBackOldItems(FileChannel oldItems) throws IOException {
			LOOP_ITEMS: while (true) {
				Entry entry = readOldItem(oldItems);
				if (entry == null)
					break;

				entry.setStoreSize(storeSize);

				long[] newOffset = entry.getOffset();

				for (int i = 0; i < newOffset.length; i++) {
					if (!lockEntry(newOffset[i])) // lock
						continue;
					try {
						if (isFree(newOffset[i])) {
							if (logDEBUG)
								Logger
								        .debug(this, "Put back old item: "
								                + HexUtil.bytesToHex(entry.digestedRoutingKey));
							writeEntry(entry, newOffset[i]);
							keyCount.incrementAndGet();
							resolvedEntries++;
							continue LOOP_ITEMS;
						}
					} catch (IOException e) {
						Logger.debug(this, "IOExcception on putBackOldItems", e);
					} finally {
						unlockEntry(newOffset[i]);
					}
				}

				if (logDEBUG)
					Logger.debug(this, "Drop old item: " + HexUtil.bytesToHex(entry.digestedRoutingKey));

				droppedEntries++;
			}
		}

		private void writeOldItem(FileChannel fc, Entry e) throws IOException {
			ByteBuffer bf = e.toByteBuffer();
			do {
				fc.write(bf);
			} while (bf.hasRemaining());
		}

		private Entry readOldItem(FileChannel fc) throws IOException {
			ByteBuffer bf = ByteBuffer.allocate((int) entryTotalLength);
			do {
				int status = fc.read(bf);
				if (status == -1)
					return null;
			} while (bf.hasRemaining());
			bf.flip();
			return new Entry(bf);
		}
	}

	public void setMaxKeys(long newStoreSize, boolean shrinkNow) throws IOException {
		Logger.normal(this, "[" + name + "] Resize newStoreSize=" + newStoreSize + ", shinkNow=" + shrinkNow);

		assert newStoreSize > 0;
		// TODO assert newStoreSize > (141 * (3 * 3) + 13 * 3) * 2; // store size too small

		configLock.writeLock().lock();
		try {
			if (newStoreSize == this.storeSize)
				return;

			if (prevStoreSize != 0) {
				if (shrinkNow) {
					// TODO shrink now
				} else {
					Logger.normal(this, "[" + name + "] resize already in progress, ignore resize request");
					return;
				}
			}

			prevStoreSize = storeSize;
			storeSize = newStoreSize;
			writeConfigFile();
			synchronized (cleanerLock) {
				cleanerLock.notifyAll();
			}

			if (shrinkNow) {
				// TODO shrink now
			}
		} finally {
			configLock.writeLock().unlock();
		}
	}

	public void setBloomSync(boolean sync) {
		configLock.writeLock().lock();
		this.syncBloom = sync;
		configLock.writeLock().unlock();
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
			Logger.debug(this, "unlocking " + offset);

		entryLock.lock();
		try {
			Condition cond = lockMap.remove(offset);
			cond.signal();
		} finally {
			entryLock.unlock();
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

				try {
					writeConfigFile();
				} catch (IOException e) {
					Logger.error(this, "error writing store config", e);
				}
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

	/**
	 * Get hashed routing key
	 *
	 * @param routingKey
	 * @return
	 */
	// TODO use a little cache?
	private byte[] getDigestedRoutingKey(byte[] routingKey) {
		MessageDigest digest = SHA256.getMessageDigest();
		try {
			digest.update(routingKey);
			digest.update(salt);

			byte[] hashedRoutingKey = digest.digest();
			assert hashedRoutingKey.length == 0x20;

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

	// ------------- Migration
	public void migrationFrom(File storeFile, File keyFile) {
		setBloomSync(false); // don't sync the bloom filter

		try {
			System.out.println("Migrating from " + storeFile);

			RandomAccessFile storeRAF = new RandomAccessFile(storeFile, "r");
			RandomAccessFile keyRAF = keyFile.exists() ? new RandomAccessFile(keyFile, "r") : null;

			byte[] header = new byte[headerBlockLength];
			byte[] data = new byte[dataBlockLength];
			byte[] key = new byte[fullKeyLength];

			long maxKey = storeRAF.length() / (headerBlockLength + dataBlockLength);
			for (int l = 0; true; l++) {
				if (l % 1024 == 0)
					System.out.println(" key " + l + "/" + maxKey);

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

		setBloomSync(true);
	}
}
