package freenet.support.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import com.db4o.ObjectContainer;

import freenet.support.Logger;
import freenet.support.api.Bucket;

/**
 * A persistent temp bucket stored as a blob in a PersistentBlobTempBucketFactory.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class PersistentBlobTempBucket implements Bucket {
	
	public final long blockSize;
	private long size;
	public final PersistentBlobTempBucketFactory factory;
	/** The index into the blob file of this specific bucket */
	final long index;
	private boolean freed;
	private boolean readOnly;
	/** Has this bucket been persisted? If not, it will be only on the temporary
	 * map in the factory. */
	private boolean persisted;
	private final int hashCode;
	final PersistentBlobTempBucketTag tag;
	
	public int hashCode() {
		return hashCode;
	}

	public PersistentBlobTempBucket(PersistentBlobTempBucketFactory factory2, long blockSize2, long slot, PersistentBlobTempBucketTag tag) {
		factory = factory2;
		blockSize = blockSize2;
		index = slot;
		hashCode = super.hashCode();
		if(tag == null) throw new NullPointerException();
		this.tag = tag;
	}

	public Bucket createShadow() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public void free() {
		factory.freeBucket(index, this); // will call onFree(): always take the outer lock first.
	}
	
	public boolean freed() {
		return freed;
	}
	
	synchronized void onFree() {
		freed = true;
	}

	boolean persisted() {
		return persisted;
	}
	
	public InputStream getInputStream() throws IOException {
		if(freed) throw new IOException("Already freed");
		final FileChannel channel = factory.channel;
		return new InputStream() {

			private int offset;
			
			@Override
			public int read() throws IOException {
				byte[] buf = new byte[1];
				int res = read(buf);
				if(res == -1) return -1;
				return buf[0];
			}
			
			@Override
			public int read(byte[] buffer, int bufOffset, int length) throws IOException {
				long max;
				synchronized(PersistentBlobTempBucket.this) {
					if(freed) throw new IOException("Bucket freed during read");
					max = Math.min(blockSize, size);
				}
				if(bufOffset < 0) return -1; // throw new EOFException() ???
				if(offset + length >= max)
					length = (int) Math.min(max - offset, Integer.MAX_VALUE);
				if(length == 0) return -1;
				ByteBuffer buf = ByteBuffer.wrap(buffer, bufOffset, length);
				int read = channel.read(buf, blockSize * index + offset);
				if(read > 0) offset += read;
				return read;
			}
			
			@Override
			public int read(byte[] buffer) throws IOException {
				return read(buffer, 0, buffer.length);
			}
			
			public int available() {
				return (int) Math.min(blockSize - offset, Integer.MAX_VALUE);
			}
			
			public void close() {
				// Do nothing.
			}
			
		};
	}

	public String getName() {
		return factory.getName()+":"+index;
	}

	public OutputStream getOutputStream() throws IOException {
		if(freed) throw new IOException("Already freed");
		if(readOnly) throw new IOException("Read-only");
		final FileChannel channel = factory.channel;
		
		return new OutputStream() {

			private int offset;
			
			@Override
			public void write(int arg) throws IOException {
				byte[] buf = new byte[] { (byte) arg };
				write(buf, 0, 1);
			}
			
			@Override
			public void write(byte[] buffer, int bufOffset, int length) throws IOException {
				synchronized(PersistentBlobTempBucket.this) {
					if(freed) throw new IOException("Bucket freed during write");
					if(readOnly) throw new IOException("Bucket made read only during write");
				}
				long remaining = blockSize - offset;
				if(remaining <= 0) throw new IOException("Too big");
				if(length > remaining) throw new IOException("Writing too many bytes: written "+offset+" of "+blockSize+" and now want to write "+length);
				ByteBuffer buf = ByteBuffer.wrap(buffer, bufOffset, length);
				int written = 0;
				while(written < length) {
					int w = channel.write(buf, blockSize * index + offset);
					offset += w;
					synchronized(PersistentBlobTempBucket.this) {
						size += w;
					}
					written += w;
				}
			}
			
			@Override
			public void write(byte[] buffer) throws IOException {
				write(buffer, 0, buffer.length);
			}
			
		};
	}

	public synchronized boolean isReadOnly() {
		return readOnly;
	}

	public synchronized void setReadOnly() {
		readOnly = true;
	}

	public synchronized long size() {
		return size;
	}

	// When created, we take up a slot in the temporary (in-RAM) map on the factory.
	// When storeTo() is called the first time, we are committed to a persistent 
	// structure. When removeFrom() is called afterwards, we are moved back to the
	// temporary map, unless we have been freed.
	
	public void storeTo(ObjectContainer container) {
		boolean p;
		// Race conditions with storeTo and removeFrom running on different threads
		// in parallel are possible... that sort of behaviour *should* be very rare,
		// you should always store it before making it publicly available...
		synchronized(this) {
			if(tag == null) throw new NullPointerException();
			p = persisted;
			persisted = true;
		}
		if(!p)
			factory.store(this, container); // Calls onStore(). Take the outer lock first.
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		synchronized(this) {
			if(persisted) return true;
		}
		Logger.error(this, "objectOnNew() called but we haven't been stored yet! for "+this+" for "+factory+" index "+index, new Exception("error"));
		return true;
		
	}
	
	public void removeFrom(ObjectContainer container) {
		boolean p;
		synchronized(this) {
			p = persisted;
		}
		if(p)
			factory.remove(this, container); // Calls onRemove().
		container.delete(this);
	}
	
	synchronized void onRemove() {
		persisted = false;
	}
	
}
