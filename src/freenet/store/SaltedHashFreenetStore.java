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

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.keys.KeyVerifyException;
import freenet.node.SemiOrderedShutdownHook;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.io.FileUtil;
import freenet.support.math.RunningAverage;
import freenet.support.math.SimpleRunningAverage;

/**
 * Index-less data store based on salted hash
 * 
 * @author sdiz
 */
public class SaltedHashFreenetStore implements FreenetStore {
	private static final boolean OPTION_SAVE_PLAINKEY = true;

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

		callback.setStore(this);
		shutdownHook.addEarlyJob(new Thread(new ShutdownDB()));

		cleanerThread = new Cleaner();
		cleanerThread.start();
	}

	public StorableBlock fetch(byte[] routingKey, byte[] fullKey, boolean dontPromote) throws IOException {
		if (logMINOR)
			Logger.minor(this, "Fetch " + HexUtil.bytesToHex(routingKey) + " for " + callback);

		Entry entry = probeEntry(routingKey);

		if (entry == null) {
			incMisses();
			return null;
		}

		try {
			StorableBlock block = entry.getStorableBlock(routingKey, fullKey);
			incHits();
			return block;
		} catch (KeyVerifyException e) {
			Logger.minor(this, "key verification exception", e);
			incMisses();
			return null;
		}
	}

	private Entry probeEntry(byte[] routingKey) throws IOException {
		Entry entry = probeEntry0(routingKey, storeSize);

		if (entry == null && prevStoreSize != 0)
			entry = probeEntry0(routingKey, prevStoreSize);

		return entry;
	}

	private Entry probeEntry0(byte[] routingKey, long probeStoreSize) throws IOException {
		Entry entry;
		long offset = getOffsetFromPlainKey(routingKey, probeStoreSize);

		if (!lockEntry(offset)) {
			Logger.error(this, "can't lock entry: " + offset);
			return null;
		}
		try {
			entry = readEntry(offset, routingKey);
		} catch (EOFException e) {
			// may occur on resize, silent it a bit
			Logger.error(this, "EOFException on probeEntry", e);
			return null;
		} finally {
			unlockEntry(offset);
		}
		return entry;
	}

	public void put(StorableBlock block, byte[] routingKey, byte[] fullKey, byte[] data, byte[] header,
	        boolean overwrite) throws IOException, KeyCollisionException {
		if (logMINOR)
			Logger.minor(this, "Putting " + HexUtil.bytesToHex(routingKey) + " for " + callback);

		StorableBlock oldBlock = fetch(routingKey, fullKey, false);

		if (oldBlock != null) {
			if (!collisionPossible)
				return;
			if (block.equals(oldBlock)) {
				return; // already in store
			} else {
				if (!overwrite)
					throw new KeyCollisionException();
			}
		}

		Entry entry = new Entry(routingKey, header, data);
		if (!lockEntry(entry.getOffset())) {
			Logger.error(this, "can't lock entry: " + entry.getOffset());
			incMisses();
			return;
		}
		try {
			writeEntry(entry);
			incWrites();
		} finally {
			unlockEntry(entry.getOffset());
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
		 *            the storeSize to set
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
			out.put(digestedRoutingKey);
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

		public long getOffset() {
			if (digestedRoutingKey != null)
				return getOffsetFromDigestedKey(digestedRoutingKey, storeSize);
			else
				return getOffsetFromPlainKey(plainRoutingKey, storeSize);
		}

		/**
		 * Verify and decrypt this entry
		 * 
		 * @param routingKey
		 * @return <code>true</code> if the <code>routeKey</code> match and the entry is
		 *         decrypted.
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
				if (!Arrays.equals(this.digestedRoutingKey, getDigestedRoutingKey(routingKey)))
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

			digestedRoutingKey = getDigestedRoutingKey(plainRoutingKey);
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
	private void writeEntry(Entry entry) throws IOException {
		entry.encrypt();

		long offset = entry.getOffset();
		int split = (int) (offset % FILE_SPLIT);
		long rawOffset = (offset / FILE_SPLIT) * entryTotalLength;

		if (logMINOR && !isFree(offset)) {
			Logger.minor(this, "collision, overwritting an entry");
		}

		ByteBuffer bf = entry.toByteBuffer();
		do {
			int status = storeFC[split].write(bf, rawOffset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());
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

		ByteBuffer bf = ByteBuffer.allocate((int) ENTRY_HEADER_LENGTH);
		do {
			int status = storeFC[split].write(bf, rawOffset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());
	}

	/**
	 * Check if a block is free
	 * 
	 * @param offset
	 * @throws IOException
	 */
	private boolean isFree(long offset) throws IOException {
		int split = (int) (offset % FILE_SPLIT);
		long rawOffset = (offset / FILE_SPLIT) * entryTotalLength;

		ByteBuffer bf = ByteBuffer.allocate(0x30 + 0x08);

		do {
			int status = storeFC[split].read(bf, rawOffset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());

		return (bf.getLong(0x30) & ENTRY_FLAG_OCCUPIED) == 0;
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
			long oldEstimatedCount = raf.readLong();
			estimatedCount = new SimpleRunningAverage(3, oldEstimatedCount);
			estimatedCount.report(oldEstimatedCount);
			raf.readLong();

			raf.close();
		}

	}

	/**
	 * Write config file
	 */
	private void writeConfigFile() throws IOException {
		File tempConfig = new File(configFile.getPath() + ".tmp");
		RandomAccessFile raf = new RandomAccessFile(tempConfig, "rw");
		raf.seek(0);
		raf.write(salt);

		raf.writeLong(storeSize);
		raf.writeLong(prevStoreSize);
		raf.writeLong(estimatedCount == null ? 0 : (long) estimatedCount.currentValue());
		raf.writeLong(0);

		raf.close();

		FileUtil.renameTo(tempConfig, configFile);
	}

	// ------------- Store resizing
	private RunningAverage estimatedCount;
	private long prevStoreSize = 0;
	private Object cleanerLock = new Object();
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

		public void run() {
			while (!shutdown) {
				synchronized (cleanerLock) {
					if (prevStoreSize != 0)
						resizeStore();
					else
						estimateStoreSize();

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

			moveOldEntry0(resizeRound > RESIZE_PHASE1_ROUND);

			if (logMINOR)
				Logger.minor(this, "Finished resize round " + resizeRound + ": newEntries=" + newEntries
				        + ", oldEntries=" + oldEntries + ", freeEntries=" + freeEntries + ", resolvedEntries="
				        + resolvedEntries + ", droppedEntries=" + droppedEntries);

			if (shutdown)
				return;

			// report key count
			estimatedCount.report(newEntries + oldEntries - droppedEntries);

			// Shrink store file size
			if (shrinking)
				setStoreFileSize(Math.max(storeSize, maxOldItemOffset));

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
					oldItemsRAF.seek(oldItemsRAF.length());
					oldItemsFC = oldItemsRAF.getChannel();
				} catch (IOException e) {
				}
			}

			long maxOffset = maxOldItemOffset;
			maxOldItemOffset = 0;
			for (long offset = 0; offset <= maxOffset; offset++) {
				if (logDEBUG && offset % 1024 == 0) {
					Logger.debug(this, "Resize progress: newEntries=" + newEntries + ", oldEntries=" + oldEntries
					        + ", freeEntries=" + freeEntries + ", resolvedEntries=" + resolvedEntries
					        + ", droppedEntries=" + droppedEntries);
				}

				if (shutdown)
					return;

				if (!lockEntry(offset)) //lock 
					continue;
				try {
					Entry entry = readEntry(offset, null);

					if (entry.isFree()) {
						// free block
						freeEntries++;
					} else if (entry.getStoreSize() == storeSize) {
						// new store size entries
						maxOldItemOffset = offset;
						newEntries++;
					} else { // if (entry.getStoreSize() == prevStoreSize)
						// old store size entries, try to move them
						oldEntries++;
						maxOldItemOffset = offset;

						entry.setStoreSize(storeSize);
						long newOffset = entry.getOffset();

						if (newOffset == offset) { // lucky! 
							writeEntry(entry); // write back entry storeSize
							resolvedEntries++;
							continue;
						}

						if (!lockEntry(newOffset)) // lock
							continue;
						try {
							// see what's in the new offset
							Entry newOffsetEntry = readEntry(newOffset, null);

							if (newOffsetEntry.isFree()) {
								// the new offset is freeeeeeee..
								writeEntry(entry);
								freeOffset(offset);
								resolvedEntries++;
							} else if (newOffsetEntry.getStoreSize() == storeSize) {
								// new offset already have a new entry, free old entry
								freeOffset(offset);
								droppedEntries++;
							} else if (Arrays.equals(entry.digestedRoutingKey, newOffsetEntry.digestedRoutingKey)) {
								// same digested routing key, free the old entry
								freeOffset(offset);
								resolvedEntries++;
							} else if (queueItem) {
								// break tie by moveing old item to queue
								if (logDEBUG)
									Logger.debug(this, "Write item "
									        + HexUtil.bytesToHex(newOffsetEntry.digestedRoutingKey)
									        + " to old item file");
								writeOldItem(oldItemsFC, newOffsetEntry);
								if (newOffset > offset) {
									oldEntries++; // newOffset wasn't counted count it
								}

								writeEntry(entry);
								freeOffset(offset);
								resolvedEntries++;
							}
						} finally {
							unlockEntry(newOffset);
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
			while (true) {
				Entry entry = readOldItem(oldItems);
				if (entry == null)
					break;

				entry.setStoreSize(storeSize);

				long newOffset = entry.getOffset();

				if (!lockEntry(newOffset)) // lock
					continue;
				boolean done = false;
				try {
					if (isFree(newOffset)) {
						if (logDEBUG)
							Logger.debug(this, "Put back old item: " + HexUtil.bytesToHex(entry.digestedRoutingKey));
						writeEntry(entry);
						done = true;
					} else {
						if (logDEBUG)
							Logger.debug(this, "Drop old item: " + HexUtil.bytesToHex(entry.digestedRoutingKey));
					}
				} catch (IOException e) {
					Logger.debug(this, "IOExcception on putBackOldItems", e);
				} finally {
					unlockEntry(newOffset);

					if (done)
						resolvedEntries++;
					else
						droppedEntries++;
				}
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
				fc.read(bf);
			} while (bf.hasRemaining());
			return new Entry(bf);
		}

		/**
		 * Samples to take on key count estimation
		 */
		private static final double SAMPLE_RATE = 0.05; // 5%
		/**
		 * Minimum samples to take on key count estimation
		 */
		private static final int MIN_SAMPLE = 10000;
		/**
		 * Last sample position
		 */
		private long samplePos = 0;

		/**
		 * Estimate store utilization
		 */
		private void estimateStoreSize() {
			Logger.minor(this, "start estimating key count");
			long numSample = Math.min((long) (SAMPLE_RATE * storeSize), MIN_SAMPLE);
			long sampled = 0;
			long occupied = 0;
			while (sampled < numSample) {
				try {
					if (!isFree(samplePos)) {
						occupied++;
					}
					sampled++;
				} catch (IOException e) { // oh, why?
					Logger.error(this, "estimating store size", e);
				}

				samplePos = (samplePos + 1) % storeSize;

				if (prevStoreSize != 0 || shutdown)
					return; // oops! time to stop here
			}

			double newEstimatedCount = ((double) occupied * storeSize) / sampled;
			estimatedCount.report(newEstimatedCount);

			if (logMINOR)
				Logger.minor(this, "finish estimating key count: sampled=" + sampled + ", occupied=" + occupied
				        + ", newEstimatedCount=" + newEstimatedCount + ", runningAverage="
				        + estimatedCount.currentValue());
		}
	}

	public void setMaxKeys(long newStoreSize, boolean shrinkNow) throws IOException {
		Logger.normal(this, "[" + name + "] Resize newStoreSize=" + newStoreSize + ", shinkNow=" + shrinkNow);

		assert newStoreSize > 0;

		synchronized (cleanerLock) {
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
			cleanerLock.notifyAll();

			if (shrinkNow) {
				// TODO shrink now
			}
		}
	}

	// ------------- Locking
	private boolean shutdown = false;
	private boolean lockGlobal = false;
	private Map lockMap = new HashMap();

	/**
	 * Lock the entry
	 * 
	 * This lock is <strong>not</strong> reentrance. No threads except Cleaner should hold more
	 * then one lock at a time (or deadlock may occur).
	 */
	private boolean lockEntry(long offset) {
		if (logDEBUG)
			Logger.debug(this, "try locking " + offset, new Exception());

		Long lxr = new Long(offset);

		try {
			synchronized (lockMap) {
				while (lockMap.containsKey(lxr) || lockGlobal) { // while someone hold the lock
					if (shutdown)
						return false;

					lockMap.wait();
				}

				lockMap.put(lxr, Thread.currentThread());
			}
		} catch (InterruptedException e) {
			Logger.error(this, "lock interrupted", e);
			return false;
		}

		if (logDEBUG)
			Logger.debug(this, "locked " + offset, new Exception());
		return true;
	}

	/**
	 * Unlock the entry
	 */
	private void unlockEntry(long offset) {
		if (logDEBUG)
			Logger.debug(this, "unlocking " + offset);
		Long lxr = new Long(offset);

		synchronized (lockMap) {
			Object o = lockMap.remove(lxr);
			assert o == Thread.currentThread();

			lockMap.notifyAll();
		}
	}

	/**
	 * Lock all entries.
	 * 
	 * Use this method to stop all read / write before database shutdown.
	 * 
	 * @param timeout
	 *            the maximum time to wait in milliseconds.
	 */
	private boolean lockGlobal(long timeout) {
		synchronized (lockMap) {
			try {
				long startTime = System.currentTimeMillis();

				while (!lockMap.isEmpty() || lockGlobal) {
					lockMap.wait(timeout);

					if (System.currentTimeMillis() - startTime > timeout)
						return false;
				}

				lockGlobal = true;
				return true;
			} catch (InterruptedException e) {
				return false;
			}
		}
	}

	/**
	 * Unlock the global lock
	 */
	private void unlockGlobal() {
		synchronized (lockMap) {
			lockGlobal = false;
			lockMap.notifyAll();
		}
	}

	public class ShutdownDB implements Runnable {
		public void run() {
			shutdown = true;

			synchronized (cleanerLock) {
				cleanerLock.notifyAll();
			}

			lockGlobal(10 * 1000); // 10 seconds

			cleanerThread.interrupt();

			flushAndClose();

			try {
				writeConfigFile();
			} catch (IOException e) {
				Logger.error(this, "error writing store config", e);
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
	public long getOffsetFromPlainKey(byte[] plainKey, long storeSize) {
		return getOffsetFromDigestedKey(getDigestedRoutingKey(plainKey), storeSize);
	}

	/**
	 * Get offset in the hash table, given a digested routing key.
	 * 
	 * @param digestedKey
	 * @param storeSize
	 * @return
	 */
	public long getOffsetFromDigestedKey(byte[] digestedKey, long storeSize) {
		long keyValue = (((long) (digestedKey[0]) << 0) + //
		        (((long) digestedKey[1]) << 8) + //
		        (((long) digestedKey[3]) << 16) + //
		        (((long) digestedKey[4]) << 24) + //
		        (((long) digestedKey[5]) << 32) + //
		        (((long) digestedKey[6]) << 40) + //
		        (((long) digestedKey[7]) << 48))
		        & Long.MAX_VALUE;
		return keyValue % storeSize;
	}

	// ------------- Statistics (a.k.a. lies)
	private final Object statLock = new Object();
	private long hits;
	private long misses;
	private long writes;

	public long hits() {
		synchronized (statLock) {
			return hits;
		}
	}

	private void incHits() {
		Logger.debug(this, "hit");
		synchronized (statLock) {
			hits++;
		}
	}

	public long misses() {
		synchronized (statLock) {
			return misses;
		}
	}

	private void incMisses() {
		Logger.debug(this, "miss");
		synchronized (statLock) {
			misses++;
		}
	}

	public long writes() {
		synchronized (statLock) {
			return writes;
		}
	}

	private void incWrites() {
		Logger.debug(this, "write");
		synchronized (statLock) {
			writes++;
		}
	}

	public long keyCount() {
		return (long) estimatedCount.currentValue();
	}

	public long getMaxKeys() {
		return storeSize;
	}

}
