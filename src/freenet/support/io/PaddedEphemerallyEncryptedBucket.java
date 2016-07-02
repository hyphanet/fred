/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Random;

import freenet.client.async.ClientContext;
import freenet.crypt.MasterSecret;
import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.math.MersenneTwister;

/**
 * A proxy Bucket which adds:
 * - Encryption with the supplied cipher, and a random, ephemeral key.
 * - Padding to the next PO2 size.
 * 
 * CRYPTO WARNING: This uses PCFB with no IV. That means it is only safe if the key is unique!
 */
public class PaddedEphemerallyEncryptedBucket implements Bucket, Serializable {

    private static final long serialVersionUID = 1L;
    private final Bucket bucket;
	private final int minPaddedSize;
	/** The decryption key. */
	private final byte[] key;
	private final byte[] iv;
	private transient byte[] randomSeed;
	private long dataLength;
	private boolean readOnly;
	private transient int lastOutputStream;
	 
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
	 * Create a padded encrypted proxy bucket.
	 * @param bucket The bucket which we are proxying to. Must be empty.
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
		this.iv = new byte[32];
		strongPRNG.nextBytes(iv);
		this.minPaddedSize = minSize;
		readOnly = false;
		lastOutputStream = 0;
		dataLength = 0;
	}

	public PaddedEphemerallyEncryptedBucket(PaddedEphemerallyEncryptedBucket orig, Bucket newBucket) {
		this.dataLength = orig.dataLength;
		this.key = orig.key.clone();
		this.randomSeed = null; // Will be read-only
		setReadOnly();
		this.bucket = newBucket;
		this.minPaddedSize = orig.minPaddedSize;
		if(orig.iv != null) {
			iv = Arrays.copyOf(orig.iv, 32);
		} else {
			iv = null;
		}
	}
	
	protected PaddedEphemerallyEncryptedBucket() {
	    // For serialization.
	    bucket = null;
	    minPaddedSize = 0;
	    key = null;
	    iv = null;
	    randomSeed = null;
	}

    @Override
	public OutputStream getOutputStream() throws IOException {
        return new BufferedOutputStream(getOutputStreamUnbuffered());
	}

    public OutputStream getOutputStreamUnbuffered() throws IOException {
        if(readOnly) throw new IOException("Read only");
        OutputStream os = bucket.getOutputStreamUnbuffered();
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
			pcfb = getPCFB();
		}
		
		@Override
		public void write(int b) throws IOException {
		    synchronized(PaddedEphemerallyEncryptedBucket.this) {
		        if(closed) throw new IOException("Already closed!");
		        if(streamNumber != lastOutputStream)
		            throw new IllegalStateException("Writing to old stream in "+getName());
		    }
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
            synchronized(PaddedEphemerallyEncryptedBucket.this) {
                if(closed)
                    throw new IOException("Already closed!");
                if(streamNumber != lastOutputStream)
                    throw new IllegalStateException("Writing to old stream in "+getName());
            }
			write(buf, 0, buf.length);
		}
		
		@Override
		public void write(byte[] buf, int offset, int length) throws IOException {
			synchronized(PaddedEphemerallyEncryptedBucket.this) {
	            if(closed) throw new IOException("Already closed!");
			    if(streamNumber != lastOutputStream)
			        throw new IllegalStateException("Writing to old stream in "+getName());
			}
			if(length == 0) return;
			byte[] enc = Arrays.copyOfRange(buf, offset, offset + length);
			pcfb.blockEncipher(enc, 0, enc.length);
			synchronized(PaddedEphemerallyEncryptedBucket.this) {
				out.write(enc, 0, enc.length);
				dataLength += enc.length;
			}
		}
		
        @Override
		@SuppressWarnings("cast")
		public void close() throws IOException {
			try {
				Random random = new MersenneTwister(randomSeed);
				synchronized(PaddedEphemerallyEncryptedBucket.this) {
		            if(closed) return;
	                if(streamNumber != lastOutputStream) {
	                    Logger.normal(this, "Not padding out to length because have been superceded: "+getName());
	                    return;
	                }
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

	@Override
	public InputStream getInputStream() throws IOException {
		return new BufferedInputStream(getInputStreamUnbuffered());
	}

    @Override
    public InputStream getInputStreamUnbuffered() throws IOException {
        return new PaddedEphemerallyEncryptedInputStream(bucket.getInputStreamUnbuffered());
    }

	private class PaddedEphemerallyEncryptedInputStream extends InputStream {

		final InputStream in;
		final PCFBMode pcfb;
		long ptr;
		
		public PaddedEphemerallyEncryptedInputStream(InputStream in) {
			this.in = in;
			pcfb = getPCFB();
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
	
	public synchronized long paddedLength() {
	    return paddedLength(dataLength, minPaddedSize);
	}

	public static final int MIN_PADDED_SIZE = 1024;
	
	/**
	 * Return the length of the data in the proxied bucket, after padding.
	 */
	public static long paddedLength(long dataLength, long minPaddedSize) {
		long size = dataLength;
		if(size < minPaddedSize) size = minPaddedSize;
		if(size == minPaddedSize) return size;
		long min = minPaddedSize;
		long max = minPaddedSize << 1;
		while(true) {
			if(max < 0)
				throw new Error("Impossible size: "+size+" - min="+min+", max="+max);
			if(size < min)
				throw new IllegalStateException("???");
			if((size >= min) && (size <= max)) {
				if(logMINOR)
					Logger.minor(PaddedEphemerallyEncryptedBucket.class, "Padded: "+max+" was: "+dataLength);
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

	@SuppressWarnings("deprecation")
	public PCFBMode getPCFB() {
		Rijndael aes = getRijndael();
		if(iv != null)
			return PCFBMode.create(aes, iv);
		else
			// FIXME CRYPTO We should probably migrate all old buckets automatically so we can get rid of this?
			// Since the key is unique it is actually almost safe to use all zeros IV, but it's better to use a real IV.
			return PCFBMode.create(aes);
	}

	@Override
	public String getName() {
		return "Encrypted:"+bucket.getName();
	}

	@Override
	public String toString() {
		return super.toString()+ ':' +bucket;
	}
	
	@Override
	public synchronized long size() {
		return dataLength;
	}

	@Override
	public boolean isReadOnly() {
		return readOnly;
	}
	
	@Override
	public void setReadOnly() {
		readOnly = true;
	}

	/**
	 * @return The underlying Bucket.
	 */
	public Bucket getUnderlying() {
		return bucket;
	}

	@Override
	public void free() {
		bucket.free();
	}

	/**
	 * Get the decryption key.
	 */
	public byte[] getKey() {
		return key;
	}

	@Override
	public Bucket createShadow() {
		Bucket newUnderlying = bucket.createShadow();
		if(newUnderlying == null) return null;
		return new PaddedEphemerallyEncryptedBucket(this, newUnderlying);
	}

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        randomSeed = new byte[32];
        context.fastWeakRandom.nextBytes(randomSeed);
        bucket.onResume(context);
    }
    
    public static final int MAGIC = 0x66c71fc9;
    static final int VERSION = 1;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(VERSION);
        dos.writeInt(minPaddedSize);
        dos.write(key);
        if(iv != null) {
            dos.writeBoolean(true);
            dos.write(iv);
        } else {
            dos.writeBoolean(false);
        }
        // randomSeed should be recovered in onResume().
        dos.writeLong(dataLength);
        dos.writeBoolean(readOnly);
        bucket.storeTo(dos);
    }
    
    protected PaddedEphemerallyEncryptedBucket(DataInputStream dis, FilenameGenerator fg, 
            PersistentFileTracker persistentFileTracker, MasterSecret masterKey) 
    throws StorageFormatException, IOException, ResumeFailedException {
        int version = dis.readInt();
        if(version != VERSION) throw new StorageFormatException("Bad version");
        minPaddedSize = dis.readInt();
        key = new byte[32];
        dis.readFully(key);
        if(dis.readBoolean()) {
            iv = new byte[32];
            dis.readFully(iv);
        } else {
            iv = null;
        }
        dataLength = dis.readLong();
        readOnly = dis.readBoolean();
        bucket = BucketTools.restoreFrom(dis, fg, persistentFileTracker, masterKey);
    }

}
