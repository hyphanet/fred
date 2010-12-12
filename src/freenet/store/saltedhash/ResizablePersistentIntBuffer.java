package freenet.store.saltedhash;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.Ticker;

/** A large resizable block of int's, which is persisted to disk with a specific policy,
 * which is either to write it on shutdown, immediately, or every X millis.
 * 
 * It would be better to do this with ByteBuffer's and an IntBuffer view, unfortunately
 * it is not possible to subclass ByteBuffer's! Also, ideally we'd memory map, but there 
 * is no way to unmap, and it is likely there will never be, so resizing would be very
 * messy and expensive.
 * @author toad
 */
public class ResizablePersistentIntBuffer {
	
	private final File filename;
	private final RandomAccessFile raf;
	private final FileChannel channel;
	private final boolean isNew;
	private int size;
	/** The buffer. When we resize we write-lock and replace this. */
	private int[] buffer;
	private final ReadWriteLock lock;
	/** -1 = write immediately, 0 = write only on shutdown, +ve = write period in millis */
	private int persistenceTime;
	private Ticker ticker;
	/** Is the buffer dirty? Protected by (this). */
	private boolean dirty;
	/** Is the writer job scheduled? Protected by (this). */
	private boolean scheduled;
	/** Is the writer job running? So we can wait for it to complete on shutdown e.g. 
	 * Protected by (this). */
	private boolean writing;
	private boolean closed;
	
	/** Create the buffer. Open the file, creating if necessary, read in the data, and set
	 * its size.
	 * @param f The filename.
	 * @param size The expected size in ints (i.e. multiply by four to get bytes).
	 * @throws IOException 
	 */
	public ResizablePersistentIntBuffer(File f, int size, int persistenceTime) throws IOException {
		this.filename = f;
		isNew = !f.exists();
		this.raf = new RandomAccessFile(f, "rw");
		this.lock = new ReentrantReadWriteLock();
		this.size = size;
		buffer = new int[size];
		long expectedLength = ((long)size)*4;
		long realLength = raf.length();
		if(realLength > expectedLength)
			raf.setLength(expectedLength);
		readBuffer((int)Math.min(size, realLength/4));
		if(realLength < expectedLength)
			raf.setLength(expectedLength);
		this.persistenceTime = persistenceTime;
		channel = raf.getChannel();
	}

	private void readBuffer(int size) throws IOException {
		raf.seek(0);
		byte[] buf = new byte[32768];
		int read = 0;
		while(read < size) {
			int toRead = (int) Math.min(buf.length, (size - read) * 4);
			raf.readFully(buf, 0, toRead);
			int[] data = Fields.bytesToInts(buf, 0, toRead);
			for(int i=0;i<data.length;i++)
				buffer[read++] = data[i];
		}
	}
	
	public void start(Ticker ticker) {
		this.ticker = ticker;
	}

	public int get(int offset) {
		lock.readLock().lock();
		if(closed) throw new IllegalStateException("Already shut down");
		try {
			return buffer[offset];
		} finally {
			lock.readLock().unlock();
		}
	}
	
	public void put(int offset, int value) throws IOException {
		put(offset, value, false);
	}

	public void put(int offset, int value, boolean noWrite) throws IOException {
		lock.readLock().lock(); // Only resize needs write lock because it creates a new buffer.
		if(closed) throw new IllegalStateException("Already shut down");
		try {
			buffer[offset] = value;
			if(persistenceTime == -1 && !noWrite) {
				channel.write(ByteBuffer.wrap(Fields.intToBytes(value)), ((long)offset)*4);
			} else if(persistenceTime > 0) {
				synchronized(this) {
					dirty = true;
					if(!scheduled) {
						ticker.queueTimedJob(writer, persistenceTime);
						scheduled = true;
					}
				}
			} else {
				synchronized(this) {
					dirty = true;
				}
			}
		} finally {
			lock.readLock().unlock();
		}
	}
	
	private Runnable writer = new Runnable() {

		public void run() {
			lock.readLock().lock(); // Protect buffer.
			try {
				synchronized(ResizablePersistentIntBuffer.this) {
					if(writing || !dirty || closed) {
						scheduled = false;
						return;
					}
					scheduled = false;
					dirty = false;
					writing = true;
				}
				try {
					writeBuffer();
				} catch (IOException e) {
					Logger.error(this, "Write failed during shutdown: "+e+" on "+filename, e);
				}
			} finally {
				synchronized(ResizablePersistentIntBuffer.this) {
					writing = false;
					notifyAll();
				}
				lock.readLock().unlock();
			}
		}
		
	};
	
	public void shutdown() {
		lock.writeLock().lock();
		try {
			synchronized(this) {
				if(closed) return;
				closed = true;
				if(writing) {
					// Wait for write to finish.
					while(writing) {
						try {
							wait();
						} catch (InterruptedException e) {
							// Ignore.
						}
					}
					if(!dirty) return;
				}
				writing = true;
			}
			try {
				writeBuffer();
			} catch (IOException e) {
				Logger.error(this, "Write failed during shutdown: "+e+" on "+filename, e);
			}
			synchronized(this) {
				writing = false;
			}
			try {
				raf.close();
			} catch (IOException e) {
				Logger.error(this, "Close failed during shutdown: "+e+" on "+filename, e);
			}
		} finally {
			lock.writeLock().unlock();
		}
		
	}

	private void writeBuffer() throws IOException {
		raf.seek(0);
		int written = 0;
		while(written < size) {
			int toWrite = (int) Math.min(32768, size - written);
			byte[] buf = Fields.intsToBytes(buffer, written, toWrite);
			raf.write(buf);
			written += toWrite;
		}
	}
	
	public void resize(int size) {
		lock.writeLock().lock();
		try {
			if(this.size == size) return;
			Logger.normal(this, "Resizing cache from "+this.size+" slots to "+size);
			this.size = size;
			int[] newBuf = new int[size];
			System.arraycopy(buffer, 0, newBuf, 0, Math.min(buffer.length, newBuf.length));
			try {
				raf.setLength(size * 4);
				writeBuffer();
			} catch (IOException e) {
				Logger.error(this, "Failed to change size or write during resize on "+filename+" : "+e, e);
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void forceWrite() {
		lock.readLock().lock();
		try {
			synchronized(this) {
				if(closed) return;
				dirty = false;
				if(writing) {
					// Wait for write to finish.
					while(writing) {
						try {
							wait();
						} catch (InterruptedException e) {
							// Ignore.
						}
					}
					if(!dirty) return;
				}
				writing = true;
			}
			try {
				writeBuffer();
			} catch (IOException e) {
				Logger.error(this, "Write failed during shutdown: "+e+" on "+filename, e);
			}
		} finally {
			synchronized(this) {
				writing = false;
			}
			lock.readLock().lock();
		}
	}

	public boolean isNew() {
		return isNew;
	}
	
}
