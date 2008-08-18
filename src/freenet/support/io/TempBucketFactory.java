/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import freenet.crypt.RandomSource;
import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

/**
 * Temporary Bucket Factory
 */
public class TempBucketFactory implements BucketFactory {
	public class TempBucket implements Bucket {
		private Bucket currentBucket;
		
		public TempBucket(Bucket cur) {
			this.currentBucket = cur;
		}
		
		public final void migrateToFileBucket() throws IOException {
			RAMBucket ramBucket = null;
			synchronized(this) {
				if(!isRAMBucket())
					return;

				ramBucket = (RAMBucket) currentBucket;
				TempFileBucket tempFB = new TempFileBucket(filenameGenerator.makeRandomFilename(), filenameGenerator);
				BucketTools.copy(currentBucket, tempFB);
				currentBucket = tempFB;
			}
			ramBucket.free();
		}
		
		public final synchronized boolean isRAMBucket() {
			return (currentBucket instanceof RAMBucket);
		}

		public synchronized OutputStream getOutputStream() throws IOException {
			return currentBucket.getOutputStream();
		}

		public synchronized InputStream getInputStream() throws IOException {
			return currentBucket.getInputStream();
		}

		public synchronized String getName() {
			return currentBucket.getName();
		}

		public synchronized long size() {
			return currentBucket.size();
		}

		public synchronized boolean isReadOnly() {
			return currentBucket.isReadOnly();
		}

		public synchronized void setReadOnly() {
			currentBucket.setReadOnly();
		}

		public synchronized void free() {
			currentBucket.free();
		}
	}

	private class RAMBucket extends ArrayBucket {
		public RAMBucket(long size) {
			super("RAMBucket", size);
			_hasTaken(size);
		}
		
		@Override
		public void free() {
			super.free();
			_hasFreed(size());
		}
	}
	
	private final FilenameGenerator filenameGenerator;
	private long bytesInUse = 0;
	
	public final static long defaultIncrement = 4096;
	
	public final static float DEFAULT_FACTOR = 1.25F;
	
	public long maxRAMBucketSize;
	public long maxRamUsed;

	private final RandomSource strongPRNG;
	private final Random weakPRNG;
	private volatile boolean reallyEncrypt;

	// Storage accounting disabled by default.
	public TempBucketFactory(FilenameGenerator filenameGenerator, long maxBucketSizeKeptInRam, long maxRamUsed, RandomSource strongPRNG, Random weakPRNG, boolean reallyEncrypt) {
		this.filenameGenerator = filenameGenerator;
		this.maxRamUsed = maxRamUsed;
		this.maxRAMBucketSize = maxBucketSizeKeptInRam;
		this.strongPRNG = strongPRNG;
		this.weakPRNG = weakPRNG;
		this.reallyEncrypt = reallyEncrypt;
	}

	public Bucket makeBucket(long size) throws IOException {
		return makeBucket(size, DEFAULT_FACTOR, defaultIncrement);
	}

	public Bucket makeBucket(long size, float factor) throws IOException {
		return makeBucket(size, factor, defaultIncrement);
	}
	
	private synchronized void _hasTaken(long size) {
		bytesInUse += size;
	}
	
	private synchronized void _hasFreed(long size) {
		bytesInUse -= size;
	}
	
	public synchronized long getRamUsed() {
		return bytesInUse;
	}
	
	public synchronized void setMaxRamUsed(long size) {
		maxRamUsed = size;
	}
	
	public synchronized long getMaxRamUsed() {
		return maxRamUsed;
	}
	
	public synchronized void setMaxRAMBucketSize(long size) {
		maxRAMBucketSize = size;
	}
	
	public synchronized long getMaxRAMBucketSize() {
		return maxRAMBucketSize;
	}
	
	public void setEncryption(boolean value) {
		reallyEncrypt = value;
	}
	
	public boolean isEncrypting() {
		return reallyEncrypt;
	}

	/**
	 * Create a temp bucket
	 * 
	 * @param size
	 *            Default size
	 * @param factor
	 *            Factor to increase size by when need more space
	 * @return A temporary Bucket
	 * @exception IOException
	 *                If it is not possible to create a temp bucket due to an
	 *                I/O error
	 */
	public TempBucket makeBucket(long size, float factor, long increment) throws IOException {
		Bucket realBucket = null;
		boolean useRAMBucket = false;
		
		synchronized(this) {
			if((size > 0) && (size <= maxRAMBucketSize) && (bytesInUse <= maxRamUsed)) {
				useRAMBucket = true;
			}
		}
		
		// Do we want a RAMBucket or a FileBucket?
		realBucket = (useRAMBucket ? new RAMBucket(size) : new TempFileBucket(filenameGenerator.makeRandomFilename(), filenameGenerator));
		// Do we want it to be encrypted?
		realBucket = (!reallyEncrypt ? realBucket : new PaddedEphemerallyEncryptedBucket(realBucket, 1024, strongPRNG, weakPRNG));
		
		return new TempBucket(realBucket);
	}
}
