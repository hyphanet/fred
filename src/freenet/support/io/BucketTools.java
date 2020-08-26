package freenet.support.io;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import freenet.support.math.MersenneTwister;

import freenet.crypt.AEADCryptBucket;

import freenet.crypt.EncryptedRandomAccessBucket;
import freenet.crypt.EncryptedRandomAccessBuffer;
import freenet.crypt.MasterSecret;
import freenet.crypt.SHA256;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.api.LockableRandomAccessBuffer;
import freenet.support.api.RandomAccessBucket;
import freenet.support.api.RandomAccessBuffer;

/**
 * Helper functions for working with Buckets.
 */
public class BucketTools {

	private static final int BUFFER_SIZE = 64 * 1024;
        
	private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	/**
	 * Copy from the input stream of <code>src</code> to the output stream of
	 * <code>dest</code>.
	 * 
	 * @param src
	 * @param dst
	 * @throws IOException
	 */
	public static void copy(Bucket src, Bucket dst) throws IOException {
		OutputStream out = dst.getOutputStreamUnbuffered();
		InputStream in = src.getInputStreamUnbuffered();
		ReadableByteChannel readChannel = Channels.newChannel(in);
		WritableByteChannel writeChannel = Channels.newChannel(out);
		try {

		// No benefit to allocateDirect() as we're wrapping streams anyway, and worse, it'd be a memory leak.
		ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
		while (readChannel.read(buffer) != -1) {
			buffer.flip();
			while(buffer.hasRemaining())
				writeChannel.write(buffer);
			buffer.clear();
		}

		} finally {
		writeChannel.close();
		readChannel.close();
		}
	}

	public static void zeroPad(Bucket b, long size) throws IOException {
		OutputStream out = b.getOutputStreamUnbuffered();

		try {
		// Initialized to zero by default.
		byte[] buffer = new byte[16384];

		long count = 0;
		while (count < size) {
			long nRequired = buffer.length;
			if (nRequired > size - count) {
				nRequired = size - count;
			}
			out.write(buffer, 0, (int) nRequired);
			count += nRequired;
		}

		} finally {
		out.close();
		}
	}

	public static void paddedCopy(Bucket from, Bucket to, long nBytes,
			int blockSize) throws IOException {

		if (nBytes > blockSize) {
			throw new IllegalArgumentException("nBytes > blockSize");
		}

		OutputStream out = null;
		InputStream in = null;

		try {

			out = to.getOutputStreamUnbuffered();
			byte[] buffer = new byte[16384];
			in = from.getInputStreamUnbuffered();

			long count = 0;
			while (count != nBytes) {
				long nRequired = nBytes - count;
				if (nRequired > buffer.length) {
					nRequired = buffer.length;
				}
				long nRead = in.read(buffer, 0, (int) nRequired);
				if (nRead == -1) {
					throw new IOException("Not enough data in source bucket.");
				}
				out.write(buffer, 0, (int) nRead);
				count += nRead;
			}

			if (count < blockSize) {
				// hmmm... better to just allocate a new buffer
				// instead of explicitly zeroing the old one?
				// Zero pad to blockSize
				long padLength = buffer.length;
				if (padLength > blockSize - nBytes) {
					padLength = blockSize - nBytes;
				}
				for (int i = 0; i < padLength; i++) {
					buffer[i] = 0;
				}

				while (count != blockSize) {
					long nRequired = blockSize - count;
					if (blockSize - count > buffer.length) {
						nRequired = buffer.length;
					}
					out.write(buffer, 0, (int) nRequired);
					count += nRequired;
				}
			}
		} finally {
			if (in != null)
				in.close();
			if (out != null)
				out.close();
		}
	}

	public static Bucket[] makeBuckets(BucketFactory bf, int count, int size)
		throws IOException {
		Bucket[] ret = new Bucket[count];
		for (int i = 0; i < count; i++) {
			ret[i] = bf.makeBucket(size);
		}
		return ret;
	}

	public static int[] nullIndices(Bucket[] array) {
		List<Integer> list = new ArrayList<Integer>();
		for (int i = 0; i < array.length; i++) {
			if (array[i] == null) {
				list.add(i);
			}
		}

		int[] ret = new int[list.size()];
		for (int i = 0; i < ret.length; i++) {
			ret[i] = list.get(i);
		}
		return ret;
	}

	public static int[] nonNullIndices(Bucket[] array) {
		List<Integer> list = new ArrayList<Integer>();
		for (int i = 0; i < array.length; i++) {
			if (array[i] != null) {
				list.add(i);
			}
		}

		int[] ret = new int[list.size()];
		for (int i = 0; i < ret.length; i++) {
			ret[i] = list.get(i);
		}
		return ret;
	}

	public static Bucket[] nonNullBuckets(Bucket[] array) {
		List<Bucket> list = new ArrayList<Bucket>(array.length);
		for (int i = 0; i < array.length; i++) {
			if (array[i] != null) {
				list.add(array[i]);
			}
		}

		Bucket[] ret = new Bucket[list.size()];
		return list.toArray(ret);
	}

	/**
	 * Read the entire bucket in as a byte array.
	 * Not a good idea unless it is very small!
	 * Don't call if concurrent writes may be happening.
	 * @throws IOException If there was an error reading from the bucket.
	 * @throws OutOfMemoryError If it was not possible to allocate enough 
	 * memory to contain the entire bucket.
	 */
	public static byte[] toByteArray(Bucket bucket) throws IOException {
		long size = bucket.size();
		if(size > Integer.MAX_VALUE) throw new OutOfMemoryError();
		byte[] data = new byte[(int)size];
		InputStream is = bucket.getInputStreamUnbuffered();
		DataInputStream dis = null;
		try {
			dis = new DataInputStream(is);
			dis.readFully(data);
		} finally {
			Closer.close(dis);
			Closer.close(is);
		}
		return data;
	}

	public static int toByteArray(Bucket bucket, byte[] output) throws IOException {
		long size = bucket.size();
		if(size > output.length)
			throw new IllegalArgumentException("Data does not fit in provided buffer");
		InputStream is = null;
		try {
			is = bucket.getInputStreamUnbuffered();
			int moved = 0;
			while(true) {
				if(moved == size) return moved;
				int x = is.read(output, moved, (int)(size - moved));
				if(x == -1) return moved;
				moved += x;
			}
		} finally {
			if(is != null) is.close();
		}
	}
	
	public static RandomAccessBucket makeImmutableBucket(BucketFactory bucketFactory, byte[] data) throws IOException {
		return makeImmutableBucket(bucketFactory, data, data.length);
	}
	
	public static RandomAccessBucket makeImmutableBucket(BucketFactory bucketFactory, byte[] data, int length) throws IOException {
		return makeImmutableBucket(bucketFactory, data, 0, length);
	}
	
	public static RandomAccessBucket makeImmutableBucket(BucketFactory bucketFactory, byte[] data, int offset, int length) throws IOException {
		RandomAccessBucket bucket = bucketFactory.makeBucket(length);
		OutputStream os = bucket.getOutputStreamUnbuffered();
		try {
		os.write(data, offset, length);
		} finally {
		os.close();
		}
		bucket.setReadOnly();
		return bucket;
	}

	public static byte[] hash(Bucket data) throws IOException {
		InputStream is = data.getInputStreamUnbuffered();
		try {
			MessageDigest md = SHA256.getMessageDigest();
			try { 
				long bucketLength = data.size();
				long bytesRead = 0;
				byte[] buf = new byte[BUFFER_SIZE];
				while ((bytesRead < bucketLength) || (bucketLength == -1)) {
					int readBytes = is.read(buf);
					if (readBytes < 0)
						break;
					bytesRead += readBytes;
					if (readBytes > 0)
						md.update(buf, 0, readBytes);
				}
				if ((bytesRead < bucketLength) && (bucketLength > 0))
					throw new EOFException();
				if ((bytesRead != bucketLength) && (bucketLength > 0))
					throw new IOException("Read " + bytesRead + " but bucket length " + bucketLength + " on " + data + '!');
				byte[] retval = md.digest();
				return retval;
			} finally {
				SHA256.returnMessageDigest(md);
			}
		} finally {
			if(is != null) is.close();
		}
	}

	/** Copy the given quantity of data from the given bucket to the given OutputStream. 
	 * @throws IOException If there was an error reading from the bucket or writing to the stream. */
	public static long copyTo(Bucket decodedData, OutputStream os, long truncateLength) throws IOException {
		if(truncateLength == 0) return 0;
		if(truncateLength < 0) truncateLength = Long.MAX_VALUE;
		InputStream is = decodedData.getInputStreamUnbuffered();
		try {
			int bufferSize = BUFFER_SIZE;
			if(truncateLength > 0 && truncateLength < bufferSize) bufferSize = (int) truncateLength;
			byte[] buf = new byte[bufferSize];
			long moved = 0;
			while(moved < truncateLength) {
				// DO NOT move the (int) inside the Math.min()! big numbers truncate to negative numbers.
				int bytes = (int) Math.min(buf.length, truncateLength - moved);
				if(bytes <= 0)
					throw new IllegalStateException("bytes="+bytes+", truncateLength="+truncateLength+", moved="+moved);
				bytes = is.read(buf, 0, bytes);
				if(bytes <= 0) {
					if(truncateLength == Long.MAX_VALUE)
						break;
					IOException ioException = new IOException("Could not move required quantity of data in copyTo: "+bytes+" (moved "+moved+" of "+truncateLength+"): unable to read from "+is);
					ioException.printStackTrace();
					throw ioException; 
				}
				os.write(buf, 0, bytes);
				moved += bytes;
			}
			return moved;
		} finally {
			is.close();
			os.flush();
		}
	}

	/** Copy data from an InputStream into a Bucket. */
	public static void copyFrom(Bucket bucket, InputStream is, long truncateLength) throws IOException {
		OutputStream os = bucket.getOutputStreamUnbuffered();
		byte[] buf = new byte[BUFFER_SIZE];
		if(truncateLength < 0) truncateLength = Long.MAX_VALUE;
		try {
			long moved = 0;
			while(moved < truncateLength) {
				// DO NOT move the (int) inside the Math.min()! big numbers truncate to negative numbers.
				int bytes = (int) Math.min(buf.length, truncateLength - moved);
				if(bytes <= 0)
					throw new IllegalStateException("bytes="+bytes+", truncateLength="+truncateLength+", moved="+moved);
				bytes = is.read(buf, 0, bytes);
				if(bytes <= 0) {
					if(truncateLength == Long.MAX_VALUE)
						break;
					IOException ioException = new IOException("Could not move required quantity of data in copyFrom: "
					        + bytes + " (moved " + moved + " of " + truncateLength + "): unable to read from " + is);
					ioException.printStackTrace();
					throw ioException;
				}
				os.write(buf, 0, bytes);
				moved += bytes;
			}
		} finally {
			os.close();
		}
	}

	/**
	 * Split the data into a series of read-only Bucket's.
	 * @param origData The original data Bucket.
	 * @param splitSize The number of bytes to put into each bucket.
	 *
	 * If the passed-in Bucket is a FileBucket, will be efficiently
	 * split into ReadOnlyFileSliceBuckets, otherwise new buckets are created
	 * and the data written to them.
	 * 
	 * Note that this method will allocate a buffer of size splitSize.
	 * @param bf
	 * @param freeData 
	 * @param persistent If true, the data is persistent. This method is responsible for ensuring that the returned
	 * buckets HAVE ALREADY BEEN STORED TO THE DATABASE, using the provided handle. The point? SegmentedBCB's buckets
	 * have already been stored!!
	 * @throws IOException If there is an error creating buckets, reading from
	 * the provided bucket, or writing to created buckets.
	 */
	public static Bucket[] split(Bucket origData, int splitSize, BucketFactory bf, boolean freeData, boolean persistent) throws IOException {
		if(origData instanceof FileBucket) {
			if(freeData) {
				Logger.error(BucketTools.class, "Asked to free data when splitting a FileBucket ?!?!? Not freeing as this would clobber the split result...");
			}
			Bucket[] buckets = ((FileBucket)origData).split(splitSize);
			if(persistent)
			return buckets;
		}
		long length = origData.size();
		if(length > ((long)Integer.MAX_VALUE) * splitSize)
			throw new IllegalArgumentException("Way too big!: "+length+" for "+splitSize);
		int bucketCount = (int) (length / splitSize);
		if(length % splitSize > 0) bucketCount++;
		if(logMINOR)
			Logger.minor(BucketTools.class, "Splitting bucket "+origData+" of size "+length+" into "+bucketCount+" buckets");
		Bucket[] buckets = new Bucket[bucketCount];
		InputStream is = origData.getInputStreamUnbuffered();
		DataInputStream dis = null;
		try {
			dis = new DataInputStream(is);
			long remainingLength = length;
			byte[] buf = new byte[splitSize];
			for(int i=0;i<bucketCount;i++) {
				int len = (int) Math.min(splitSize, remainingLength);
				Bucket bucket = bf.makeBucket(len);
				buckets[i] = bucket;
				dis.readFully(buf, 0, len);
				remainingLength -= len;
				OutputStream os = bucket.getOutputStreamUnbuffered();
				try {
					os.write(buf, 0, len);
				} finally {
					os.close();
				}
			}
		} finally {
			if(dis != null)
				dis.close();
			else
				is.close();
		}
		if(freeData)
			origData.free();
		return buckets;
	}
	
	/**
	 * Pad a bucket with random data
	 * 
	 * @param oldBucket
	 * @param blockLength
	 * @param bf
	 * @param length
	 * 
	 * @return the paded bucket
	 */
	public static Bucket pad(Bucket oldBucket, int blockLength, BucketFactory bf, int length) throws IOException {
		byte[] hash = BucketTools.hash(oldBucket);
		Bucket b = bf.makeBucket(blockLength);
		MersenneTwister mt = new MersenneTwister(hash);
		OutputStream os = b.getOutputStreamUnbuffered();
		try {
			BucketTools.copyTo(oldBucket, os, length);
			byte[] buf = new byte[BUFFER_SIZE];
			for(int x=length;x<blockLength;) {
				int remaining = blockLength - x;
				int thisCycle = Math.min(remaining, buf.length);
				mt.nextBytes(buf); // FIXME??
				os.write(buf, 0, thisCycle);
				x += thisCycle;
			}
			os.close();
			os = null;
			if(b.size() != blockLength)
				throw new IllegalStateException("The bucket's size is "+b.size()+" whereas it should be "+blockLength+'!');
			return b;
		} finally { Closer.close(os); }
	}
	
	static final ArrayBucketFactory ARRAY_FACTORY = new ArrayBucketFactory();
	
    public static byte[] pad(byte[] orig, int blockSize, int length) throws IOException {
        ArrayBucket b = new ArrayBucket(orig);
        Bucket ret = BucketTools.pad(b, blockSize, ARRAY_FACTORY, length);
        return BucketTools.toByteArray(ret);
    }

    public static boolean equalBuckets(Bucket a, Bucket b) throws IOException {
        if(a.size() != b.size()) return false;
        long size = a.size();
        InputStream aIn = null, bIn = null;
        try {
            aIn = a.getInputStreamUnbuffered();
            bIn = b.getInputStreamUnbuffered();
            return FileUtil.equalStreams(aIn, bIn, size);
        } finally {
            aIn.close();
            bIn.close();
        }
    }
    
    /** @deprecated Only for unit tests */
    @Deprecated
    public static void fill(Bucket bucket, Random random, long length) throws IOException {
        OutputStream os = null;
        try {
            os = bucket.getOutputStreamUnbuffered();
            FileUtil.fill(os, random, length);
        } finally {
            if(os != null) os.close();
        }
    }

    /** @deprecated Only for unit tests */
    @Deprecated
    public static void fill(RandomAccessBuffer raf, Random random, long offset, long length) 
    throws IOException {
        long moved = 0;
        byte[] buf = new byte[BUFFER_SIZE];
        while(moved < length) {
            int toRead = (int)Math.min(BUFFER_SIZE, length - moved);
            random.nextBytes(buf);
            raf.pwrite(offset + moved, buf, 0, toRead);
            moved += toRead;
        }
    }

    /** Fill a bucket with hard to identify random data */
    public static void fill(Bucket bucket, long length) throws IOException {
        OutputStream os = null;
        try {
            os = bucket.getOutputStreamUnbuffered();
            FileUtil.fill(os, length);
        } finally {
            if(os != null) os.close();
        }
    }

    /** Copy the contents of a Bucket to a RandomAccessBuffer at a specific offset.
     * @param bucket The bucket to read data from.
     * @param raf The RandomAccessBuffer to write to.
     * @param fileOffset The offset within raf to start writing at.
     * @param truncateLength The maximum number of bytes to transfer, or -1 to copy the whole 
     * bucket. 
     * @return The number of bytes moved.
     * @throws IOException If something breaks while copying the data. */
    public static long copyTo(Bucket bucket, RandomAccessBuffer raf, long fileOffset, 
            long truncateLength) throws IOException {
        if(truncateLength == 0) return 0;
        if(truncateLength < 0) truncateLength = Long.MAX_VALUE;
        InputStream is = bucket.getInputStreamUnbuffered();
        try {
            int bufferSize = BUFFER_SIZE;
            if(truncateLength > 0 && truncateLength < bufferSize) bufferSize = (int) truncateLength;
            byte[] buf = new byte[bufferSize];
            long moved = 0;
            while(moved < truncateLength) {
                // DO NOT move the (int) inside the Math.min()! big numbers truncate to negative numbers.
                int bytes = (int) Math.min(buf.length, truncateLength - moved);
                if(bytes <= 0)
                    throw new IllegalStateException("bytes="+bytes+", truncateLength="+truncateLength+", moved="+moved);
                bytes = is.read(buf, 0, bytes);
                if(bytes <= 0) {
                    if(truncateLength == Long.MAX_VALUE)
                        break;
                    IOException ioException = new IOException("Could not move required quantity of data in copyTo: "+bytes+" (moved "+moved+" of "+truncateLength+"): unable to read from "+is);
                    ioException.printStackTrace();
                    throw ioException; 
                }
                raf.pwrite(fileOffset, buf, 0, bytes);
                moved += bytes;
                fileOffset += bytes;
            }
            return moved;
        } finally {
            is.close();
        }
    }
    
    /** Inverse of Bucket.storeTo(). Uses the magic value to identify the bucket type.
     * FIXME Maybe we should just pass the ClientContext? 
     * @throws IOException 
     * @throws StorageFormatException 
     * @throws ResumeFailedException */
    public static Bucket restoreFrom(DataInputStream dis, FilenameGenerator fg, 
            PersistentFileTracker persistentFileTracker, MasterSecret masterKey) 
    throws IOException, StorageFormatException, ResumeFailedException {
        int magic = dis.readInt();
        switch(magic) {
        case AEADCryptBucket.MAGIC:
            return new AEADCryptBucket(dis, fg, persistentFileTracker, masterKey);
        case FileBucket.MAGIC:
            return new FileBucket(dis);
        case PersistentTempFileBucket.MAGIC:
            return new PersistentTempFileBucket(dis);
        case DelayedFreeBucket.MAGIC:
            return new DelayedFreeBucket(dis, fg, persistentFileTracker, masterKey);
        case DelayedFreeRandomAccessBucket.MAGIC:
            return new DelayedFreeRandomAccessBucket(dis, fg, persistentFileTracker, masterKey);
        case NoFreeBucket.MAGIC:
            return new NoFreeBucket(dis, fg, persistentFileTracker, masterKey);
        case PaddedEphemerallyEncryptedBucket.MAGIC:
            return new PaddedEphemerallyEncryptedBucket(dis, fg, persistentFileTracker, masterKey);
        case ReadOnlyFileSliceBucket.MAGIC:
            return new ReadOnlyFileSliceBucket(dis);
        case PaddedBucket.MAGIC:
            return new PaddedBucket(dis, fg, persistentFileTracker, masterKey);
        case PaddedRandomAccessBucket.MAGIC:
            return new PaddedRandomAccessBucket(dis, fg, persistentFileTracker, masterKey);
        case RAFBucket.MAGIC:
            return new RAFBucket(dis, fg, persistentFileTracker, masterKey);
        case EncryptedRandomAccessBucket.MAGIC:
            return new EncryptedRandomAccessBucket(dis, fg, persistentFileTracker, masterKey);
        default:
            throw new StorageFormatException("Unknown magic value for bucket "+magic);
        }
    }
    
    /** Restore a LockableRandomAccessBuffer from a DataInputStream. Inverse of storeTo().
     * FIXME Maybe we should just pass the ClientContext? 
     */
    public static LockableRandomAccessBuffer restoreRAFFrom(DataInputStream dis, 
            FilenameGenerator fg, PersistentFileTracker persistentFileTracker, MasterSecret masterSecret)
    throws IOException, StorageFormatException, ResumeFailedException {
        int magic = dis.readInt();
        switch(magic) {
        case PooledFileRandomAccessBuffer.MAGIC:
            return new PooledFileRandomAccessBuffer(dis, fg, persistentFileTracker);
        case FileRandomAccessBuffer.MAGIC:
            return new FileRandomAccessBuffer(dis);
        case ReadOnlyRandomAccessBuffer.MAGIC:
            return new ReadOnlyRandomAccessBuffer(dis, fg, persistentFileTracker, masterSecret);
        case DelayedFreeRandomAccessBuffer.MAGIC:
            return new DelayedFreeRandomAccessBuffer(dis, fg, persistentFileTracker, masterSecret);
        case EncryptedRandomAccessBuffer.MAGIC:
            return EncryptedRandomAccessBuffer.create(dis, fg, persistentFileTracker, masterSecret);
        case PaddedRandomAccessBuffer.MAGIC:
            return new PaddedRandomAccessBuffer(dis, fg, persistentFileTracker, masterSecret);
        default:
            throw new StorageFormatException("Unknown magic value for RAF "+magic);
        }
    }
    
    public static RandomAccessBucket toRandomAccessBucket(Bucket bucket, BucketFactory bf) throws IOException {
        if(bucket instanceof RandomAccessBucket)
            return (RandomAccessBucket)bucket;
        if(bucket instanceof DelayedFreeBucket) {
            RandomAccessBucket ret = ((DelayedFreeBucket)bucket).toRandomAccessBucket();
            if(ret != null) return ret;
        }
        RandomAccessBucket ret = bf.makeBucket(bucket.size());
        BucketTools.copy(bucket, ret);
        bucket.free();
        return ret;
    }

}
