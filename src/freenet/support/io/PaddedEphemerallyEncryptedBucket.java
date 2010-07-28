/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

import org.spaceroots.mantissa.random.MersenneTwister;

import com.db4o.ObjectContainer;

import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

/**
 * A proxy Bucket which adds:
 * - Encryption with the supplied cipher, and a random, ephemeral key.
 * - Padding to the next PO2 size.
 * 
 * CRYPTO WARNING: This uses PCFB with no IV. That means it is only safe if the key is unique!
 */
public class PaddedEphemerallyEncryptedBucket implements Bucket, SerializableToFieldSetBucket {

	private final Bucket bucket;
	private final int minPaddedSize;
	/** The decryption key. */
	private final byte[] key;
	private final byte[] randomSeed;
	private long dataLength;
	private boolean readOnly;
	private int lastOutputStream;
	
	/**
	 * Create a padded encrypted proxy bucket.
	 * @param bucket The bucket which we are proxying to. Must be empty.
	 * @param pcfb The encryption mode with which to encipher/decipher the data.
	 * @param minSize The minimum padded size of the file (after it has been closed).
	 * @param strongPRNG a strong prng we will key from.
	 * @param weakPRNG a week prng we will padd from.
	 * Serialization: Note that it is not our responsibility to free the random number generators,
	 * but we WILL free the underlying bucket.
	 * @throws UnsupportedCipherException 
	 */
	public PaddedEphemerallyEncryptedBucket(Bucket bucket, int minSize, RandomSource strongPRNG, Random weakPRNG) {
		this.bucket = bucket;
		if(bucket.size() != 0) throw new IllegalArgumentException("Bucket must be empty");
		byte[] tempKey = new byte[32];
		randomSeed = new byte[32];
		weakPRNG.nextBytes(randomSeed);
		strongPRNG.nextBytes(tempKey);
		this.key = tempKey;
		this.minPaddedSize = minSize;
		readOnly = false;
		lastOutputStream = 0;
		dataLength = 0;
	}

	/**
	 * Load an existing PaddedEphemerallyEncryptedBucket, with a key.
	 * The bucket can and should already exist.
	 * @param bucket
	 * @param minSize
	 * @param knownSize The size of the data. This cannot be deduced from the bucket
	 * alone and must be specified. If the bucket is smaller than this, we throw.
	 * @param key
	 * @param origRandom
	 * @throws IOException 
	 */
	public PaddedEphemerallyEncryptedBucket(Bucket bucket, int minSize, long knownSize, byte[] key, RandomSource origRandom) throws IOException {
		if(bucket.size() < knownSize)
			throw new IOException("Bucket "+bucket+" is too small on disk - knownSize="+knownSize+" but bucket.size="+bucket.size()+" for "+bucket);
		this.dataLength = knownSize;
		this.bucket = bucket;
		if(key.length != 32) throw new IllegalArgumentException("Key wrong length: "+key.length);
		randomSeed = new byte[32];
		origRandom.nextBytes(randomSeed);
		this.key = key;
		this.minPaddedSize = minSize;
		readOnly = false;
		lastOutputStream = 0;
	}

	public PaddedEphemerallyEncryptedBucket(SimpleFieldSet fs, RandomSource origRandom, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
		String tmp = fs.get("DataLength");
		if(tmp == null)
			throw new CannotCreateFromFieldSetException("No DataLength");
		try {
			dataLength = Long.parseLong(tmp);
		} catch (NumberFormatException e) {
			throw new CannotCreateFromFieldSetException("Corrupt dataLength: "+tmp, e);
		}
		SimpleFieldSet underlying = fs.subset("Underlying");
		if(underlying == null)
			throw new CannotCreateFromFieldSetException("No underlying bucket");
		bucket = SerializableToFieldSetBucketUtil.create(underlying, origRandom, f);
		tmp = fs.get("DecryptKey");
		if(tmp == null)
			throw new CannotCreateFromFieldSetException("No key");
		key = HexUtil.hexToBytes(tmp);
		if(key.length != 32) throw new IllegalArgumentException("Key wrong length: "+key.length);
		tmp = fs.get("MinPaddedSize");
		if(tmp == null)
			throw new CannotCreateFromFieldSetException("No MinPaddedSize!");
		else {
			try {
				minPaddedSize = Integer.parseInt(tmp);
			} catch (NumberFormatException e) {
				throw new CannotCreateFromFieldSetException("Corrupt dataLength: "+tmp, e);
			}
		}
		if(dataLength > bucket.size())
			throw new CannotCreateFromFieldSetException("Underlying bucket "+bucket+" is too small: should be "+dataLength+" actually "+bucket.size());
		randomSeed = new byte[32];
		origRandom.nextBytes(randomSeed);
	}

	public PaddedEphemerallyEncryptedBucket(PaddedEphemerallyEncryptedBucket orig, Bucket newBucket) {
		this.dataLength = orig.dataLength;
		this.key = new byte[orig.key.length];
		System.arraycopy(orig.key, 0, key, 0, orig.key.length);
		this.randomSeed = null; // Will be read-only
		setReadOnly();
		this.bucket = newBucket;
		this.minPaddedSize = orig.minPaddedSize;
	}

	public OutputStream getOutputStream() throws IOException {
		if(readOnly) throw new IOException("Read only");
		OutputStream os = bucket.getOutputStream();
		synchronized(this) {
			dataLength = 0;
		}
		return new PaddedEphemerallyEncryptedOutputStream(os, ++lastOutputStream);
	}

	private class PaddedEphemerallyEncryptedOutputStream extends OutputStream {

		final PCFBMode pcfb;
		final OutputStream out;
		final int streamNumber;
		private boolean closed;
		
		public PaddedEphemerallyEncryptedOutputStream(OutputStream out, int streamNumber) {
			this.out = out;
			dataLength = 0;
			this.streamNumber = streamNumber;
			Rijndael aes = getRijndael();
			pcfb = PCFBMode.create(aes);
		}
		
		@Override
		public void write(int b) throws IOException {
			if(closed) throw new IOException("Already closed!");
			if(streamNumber != lastOutputStream)
				throw new IllegalStateException("Writing to old stream in "+getName());
			//if((b < 0) || (b > 255))
			//	throw new IllegalArgumentException();
			int toWrite = pcfb.encipher(b);
			synchronized(PaddedEphemerallyEncryptedBucket.this) {
				out.write(toWrite);
				dataLength++;
			}
		}
		
		// Override this or FOS will use write(int)
		@Override
		public void write(byte[] buf) throws IOException {
			if(closed)
				throw new IOException("Already closed!");
			if(streamNumber != lastOutputStream)
				throw new IllegalStateException("Writing to old stream in "+getName());
			write(buf, 0, buf.length);
		}
		
		@Override
		public void write(byte[] buf, int offset, int length) throws IOException {
			if(closed) throw new IOException("Already closed!");
			if(streamNumber != lastOutputStream)
				throw new IllegalStateException("Writing to old stream in "+getName());
			if(length == 0) return;
			byte[] enc = new byte[length];
			System.arraycopy(buf, offset, enc, 0, length);
			pcfb.blockEncipher(enc, 0, enc.length);
			synchronized(PaddedEphemerallyEncryptedBucket.this) {
				out.write(enc, 0, enc.length);
				dataLength += enc.length;
			}
		}
		
        @Override
		@SuppressWarnings("cast")
		public void close() throws IOException {
			if(closed) return;
			try {
				if(streamNumber != lastOutputStream) {
					Logger.normal(this, "Not padding out to length because have been superceded: "+getName());
					return;
				}
				Random random = new MersenneTwister(randomSeed);
				synchronized(PaddedEphemerallyEncryptedBucket.this) {
					long finalLength = paddedLength();
					long padding = finalLength - dataLength;
					int sz = 65536;
					if(padding < (long)sz)
						sz = (int)padding;
					byte[] buf = new byte[sz];
					long writtenPadding = 0;
					while(writtenPadding < padding) {
						int left = (int) Math.min((long) (padding - writtenPadding), (long) buf.length);
						random.nextBytes(buf);
						out.write(buf, 0, left);
						writtenPadding += left;
					}
				}
			} finally {
				closed = true;
				out.flush();
				out.close();
			}
		}
	}

	public InputStream getInputStream() throws IOException {
		return new PaddedEphemerallyEncryptedInputStream(bucket.getInputStream());
	}

	private class PaddedEphemerallyEncryptedInputStream extends InputStream {

		final InputStream in;
		final PCFBMode pcfb;
		long ptr;
		
		public PaddedEphemerallyEncryptedInputStream(InputStream in) {
			this.in = in;
			Rijndael aes = getRijndael();
			pcfb = PCFBMode.create(aes);
			ptr = 0;
		}
		
		@Override
		public int read() throws IOException {
			if(ptr >= dataLength) return -1;
			int x = in.read();
			if(x == -1) return x;
			ptr++;
			return pcfb.decipher(x);
		}
		
		@Override
		public final int available() {
			int x = (int)Math.min(dataLength - ptr, Integer.MAX_VALUE);
			return (x < 0) ? 0 : x;
		}
		
		@Override
		public int read(byte[] buf, int offset, int length) throws IOException {
			// FIXME remove debugging
			if((length+offset > buf.length) || (offset < 0) || (length < 0))
				throw new ArrayIndexOutOfBoundsException("a="+offset+", b="+length+", length "+buf.length);
			int x = available();
			if(x <= 0) return -1;
			length = Math.min(length, x);
			int readBytes = in.read(buf, offset, length);
			if(readBytes <= 0) return readBytes;
			ptr += readBytes;
			pcfb.blockDecipher(buf, offset, readBytes);
			return readBytes;
		}

		@Override
		public int read(byte[] buf) throws IOException {
			return read(buf, 0, buf.length);
		}
		
		@Override
		public long skip(long bytes) throws IOException {
			byte[] buf = new byte[(int)Math.min(4096, bytes)];
			long skipped = 0;
			while(skipped < bytes) {
				int x = read(buf, 0, (int)Math.min(bytes-skipped, buf.length));
				if(x <= 0) return skipped;
				skipped += x;
			}
			return skipped;
		}
		
		@Override
		public void close() throws IOException {
			in.close();
		}
	}

	/**
	 * Return the length of the data in the proxied bucket, after padding.
	 */
	public synchronized long paddedLength() {
		long size = dataLength;
		if(size < minPaddedSize) size = minPaddedSize;
		if(size == minPaddedSize) return size;
		long min = minPaddedSize;
		long max = (long)minPaddedSize << 1;
		while(true) {
			if(max < 0)
				throw new Error("Impossible size: "+size+" - min="+min+", max="+max);
			if(size < min)
				throw new IllegalStateException("???");
			if((size >= min) && (size <= max)) {
				if(Logger.shouldLog(LogLevel.MINOR, this))
					Logger.minor(this, "Padded: "+max+" was: "+dataLength+" for "+getName());
				return max;
			}
			min = max;
			max = max << 1;
		}
	}

	private synchronized Rijndael getRijndael() {
		Rijndael aes;
		try {
			aes = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			throw new Error(e);
		}
		aes.initialize(key);
		return aes;
	}

	public String getName() {
		return "Encrypted:"+bucket.getName();
	}

	@Override
	public String toString() {
		return super.toString()+ ':' +bucket;
	}
	
	public synchronized long size() {
		return dataLength;
	}

	public boolean isReadOnly() {
		return readOnly;
	}
	
	public void setReadOnly() {
		readOnly = true;
	}

	/**
	 * @return The underlying Bucket.
	 */
	public Bucket getUnderlying() {
		return bucket;
	}

	public void free() {
		bucket.free();
	}

	/**
	 * Get the decryption key.
	 */
	public byte[] getKey() {
		return key;
	}

	public SimpleFieldSet toFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.putSingle("Type", "PaddedEphemerallyEncryptedBucket");
		synchronized(this) {
			fs.put("DataLength", dataLength);
		}
		if(key != null) {
			fs.putSingle("DecryptKey", HexUtil.bytesToHex(key));
		} else {
			Logger.error(this, "Cannot serialize because no key");
			return null;
		}
		if(bucket instanceof SerializableToFieldSetBucket) {
			fs.put("Underlying", ((SerializableToFieldSetBucket)bucket).toFieldSet());
		} else {
			Logger.error(this, "Cannot serialize underlying bucket: "+bucket);
			return null;
		}
		fs.put("MinPaddedSize", minPaddedSize);
		return fs;
	}

	public void storeTo(ObjectContainer container) {
		bucket.storeTo(container);
		container.store(this);
	}

	public void removeFrom(ObjectContainer container) {
		if(Logger.shouldLog(LogLevel.MINOR, this))
			Logger.minor(this, "Removing from database: "+this);
		bucket.removeFrom(container);
		container.delete(this);
	}
	
	public void objectOnActivate(ObjectContainer container) {
		Logger.minor(this, "Activating "+super.toString()+" bucket == null = "+(bucket == null));
		// Cascading activation of dependancies
		container.activate(bucket, 1);
	}
	
	public Bucket createShadow() throws IOException {
		Bucket newUnderlying = bucket.createShadow();
		if(newUnderlying == null) return null;
		return new PaddedEphemerallyEncryptedBucket(this, newUnderlying);
	}

}
