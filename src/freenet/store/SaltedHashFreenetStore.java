/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.store;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import freenet.crypt.Digest;
import freenet.crypt.SHA1;
import freenet.keys.KeyVerifyException;
import freenet.node.SemiOrderedShutdownHook;
import freenet.support.HexUtil;
import freenet.support.Logger;

/**
 * Index-less data store based on salted hash
 * 
 * @author sdiz
 */
public class SaltedHashFreenetStore implements FreenetStore {
	private static boolean logMINOR;
	private static boolean logDEBUG;

	private final File baseDir;
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

	SaltedHashFreenetStore(File baseDir, String name, StoreCallback callback, Random random, long maxKeys,
	        SemiOrderedShutdownHook shutdownHook) throws IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		logDEBUG = Logger.shouldLog(Logger.DEBUG, this);

		this.baseDir = baseDir;

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
		// TODO probe store resize
		return probeEntry0(routingKey, storeSize);
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
			//TODO store resize
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
	private final static int FILE_SPLIT = 0x20;
	private File[] storeFiles;
	private RandomAccessFile[] storeRAF;
	private FileChannel[] storeFC;

	/** Flag for occupied space */
	private final long ENTRY_FLAG_OCCUPIED = 0x00000001L;

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
	 *  |0020|       Data Encrypt Key        |
	 *  +----+---------------+---------------+
	 *  |0030|     Flag      |  Store Size   |
	 *  +----+---------------+---------------+
	 *  |0040|            Reserved           |
	 *  |0050|            Reserved           |
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
		private byte[] routingKey;
		private byte[] dataEncryptKey;
		private long flag;
		private long storeSize;
		private byte[] header;
		private byte[] data;

		private boolean isEncrypted;

		/**
		 * Create a new entry
		 * 
		 * @param routingKey
		 * @param header
		 * @param data
		 */
		public Entry(byte[] routingKey, byte[] header, byte[] data) {
			this.routingKey = routingKey;
			flag = ENTRY_FLAG_OCCUPIED;
			storeSize = SaltedHashFreenetStore.this.storeSize;

			// header/data will be overwritten in flip(),
			// let's make a copy here
			this.header = new byte[headerBlockLength];
			System.arraycopy(header, 0, this.header, 0, headerBlockLength);
			this.data = new byte[dataBlockLength];
			System.arraycopy(data, 0, this.data, 0, dataBlockLength);

			isEncrypted = false;
		}

		public Entry(ByteBuffer in) {
			assert in.remaining() == entryTotalLength;

			routingKey = new byte[0x20];
			in.get(routingKey);

			dataEncryptKey = new byte[0x10];
			in.get(dataEncryptKey);

			flag = in.getLong();
			storeSize = in.getLong();

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
			out.put(routingKey);
			out.put(dataEncryptKey);

			out.putLong(flag);
			out.putLong(storeSize);

			// reserved bytes
			out.position((int) ENTRY_HEADER_LENGTH);

			out.put(header);
			out.put(data);

			assert out.remaining() == entryPaddingLength;
			out.position(0);

			return out;
		}

		public StorableBlock getStorableBlock(byte[] routingKey, byte[] fullKey) throws KeyVerifyException {
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
			BigInteger bi = new BigInteger(isEncrypted ? routingKey : getDigestedRoutingKey(routingKey));
			return bi.mod(BigInteger.valueOf(storeSize)).longValue();
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
				if (Arrays.equals(this.routingKey, routingKey))
					return true;
				else
					return false;
			}

			// Does the digested routing key match?
			if (!Arrays.equals(this.routingKey, getDigestedRoutingKey(routingKey)))
				return false;

			flip(routingKey);

			this.routingKey = routingKey;
			isEncrypted = false;

			return true;
		}

		/**
		 * Encrypt this entry
		 */
		private void encrypt() {
			if (isEncrypted)
				return;

			dataEncryptKey = new byte[16];
			random.nextBytes(dataEncryptKey);

			flip(routingKey);

			routingKey = getDigestedRoutingKey(routingKey);
			isEncrypted = true;
		}

		/**
		 * Encrypt / Decrypt header and data
		 * 
		 * @param routingKey
		 */
		private void flip(byte[] routingKey) {
			Digest digest = SHA1.getInstance();

			int pos = 0;
			for (byte i = 0; true; i++) {
				digest.update(dataEncryptKey);
				digest.update(routingKey);
				digest.update(i);
				byte[] otp = digest.digest();

				for (int j = 0; j < otp.length && pos < header.length; j++, pos++)
					header[pos] ^= otp[j];

				if (pos == header.length)
					break;
			}

			pos = 0;
			for (byte i = 0; true; i++) {
				digest.update(i); // reverse the order for data
				digest.update(routingKey);
				digest.update(dataEncryptKey);
				byte[] otp = digest.digest();

				for (int j = 0; j < otp.length && pos < data.length; j++, pos++)
					data[pos] ^= otp[j];

				if (pos == data.length)
					break;
			}
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
			//TODO store resize
			if (storeRAF[i].length() == 0) { // New file?
				storeRAF[i].setLength(entryTotalLength * (storeSize / FILE_SPLIT + 1));
			}
			storeFC[i] = storeRAF[i].getChannel();
			storeFC[i].lock();
		}
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

		ByteBuffer bf = ByteBuffer.allocate((int) ENTRY_HEADER_LENGTH);

		do {
			int status = storeFC[split].write(bf, rawOffset + bf.position());
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
	 *  |0010|   Store Size  | prevStoreSize1|
	 *  +----+---------------+---------------+
	 *  |0010| prevStoreSize2| prevStoreSize3|
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
			raf.read(salt);

			// TODO store resize
			storeSize = raf.readLong();
			raf.readLong();
			raf.readLong();
			raf.readLong();

			raf.close();
		}

	}

	/**
	 * Write config file
	 */
	private void writeConfigFile() throws IOException {
		RandomAccessFile raf = new RandomAccessFile(configFile, "rw");
		raf.seek(0);
		raf.write(salt);
		raf.writeLong(storeSize);

		// TODO store resize
		raf.writeLong(0);
		raf.writeLong(0);
		raf.writeLong(0);

		raf.close();
	}

	// ------------- Store resizing
	public void setMaxKeys(long maxStoreKeys, boolean shrinkNow) throws IOException {
		// TODO
		// NO-OP now
	}

	// ------------- Locking
	private boolean shutdown = false;
	private boolean lockGlobal = false;
	private Map lockMap = new HashMap();

	/**
	 * Lock the entry // TODO locking
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
	 * Unlock the entry // TODO locking
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
			lockGlobal(10 * 1000); // 10 seconds
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
		Digest digest = SHA1.getInstance();
		digest.update(routingKey);
		digest.update(salt);

		byte[] digestedKey = digest.digest();
		byte[] hashedRoutingKey = new byte[0x20];

		// SHA-1 is only 160-bits, must fill something on lower order bytes
		System.arraycopy(digestedKey, 0, //
		        hashedRoutingKey, hashedRoutingKey.length - digestedKey.length,//
		        digestedKey.length);

		return hashedRoutingKey;
	}

	/**
	 * Get offset in the hash table, given a plain routing key.
	 * 
	 * @param routingKey
	 * @param storeSize
	 * @return
	 */
	public long getOffsetFromPlainKey(byte[] routingKey, long storeSize) {
		// Don't use NativeBigInteger, {@link net.i2p.util.NativeBigInteger#mod()} don't use native routine.
		BigInteger bi = new BigInteger(getDigestedRoutingKey(routingKey));
		return bi.mod(BigInteger.valueOf(storeSize)).longValue();
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
		return 0;
	}

	public long getMaxKeys() {
		return storeSize;
	}
}
