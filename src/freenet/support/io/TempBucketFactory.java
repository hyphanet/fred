package freenet.support.io;

import freenet.crypt.RandomSource;
import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/*
 * This code is part of FProxy, an HTTP proxy server for Freenet. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */
import java.util.Random;

/**
 * Temporary Bucket Factory
 * 
 * @author giannij
 */
public class TempBucketFactory implements BucketFactory {

	private class RAMBucket extends ArrayBucket {
		private final long size;
		
		public RAMBucket(long size) {
			super("RAMBucket");
			this.size = size;
		}
		
		@Override
		public void free() {
			super.free();
			_hasFreed(size);
		}
	}
	
	private final FilenameGenerator filenameGenerator;
	private long bytesInUse = 0;
	
	public final static long defaultIncrement = 4096;
	
	public final static float DEFAULT_FACTOR = 1.25F;
	
	public long maxRAMBucketSize;
	public long maxRamUsed;

	final RandomSource strongPRNG;
	final Random weakPRNG;
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
	
	protected synchronized void _hasFreed(long size) {
		bytesInUse -= size;
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
	public Bucket makeBucket(long size, float factor, long increment) throws IOException {
		Bucket realBucket = null;
		boolean isARAMBucket = false;
		
		synchronized(this) {
			if((size > 0) && (size <= maxRAMBucketSize) && (bytesInUse <= maxRamUsed)) {
				bytesInUse += size;
				isARAMBucket = true;
			}
		}
		
		realBucket = (isARAMBucket ? new RAMBucket(size) : new TempFileBucket(filenameGenerator.makeRandomFilename(), filenameGenerator));
		
		return (!reallyEncrypt ? realBucket : new PaddedEphemerallyEncryptedBucket(realBucket, 1024, strongPRNG, weakPRNG));
	}
}
