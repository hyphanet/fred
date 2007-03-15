// REDFLAG: test and javadoc
package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Vector;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Bucket implementation that can efficiently access any arbitrary byte-range
 * of a file.
 *
 **/
public class RandomAccessFileBucket implements Bucket, SerializableToFieldSetBucket {

    private final File file;
    private final long offset;
    private long localOffset = 0;
    private final long len;
    private boolean readOnly = false;
    private boolean released = false;
    private Vector streams = new Vector();
    
    public RandomAccessFileBucket(File file, long offset, long len, boolean readOnly)
        throws IOException {
        if (!(file.exists() && file.canRead())) {
            throw new IOException("Can't read file: " + file.getAbsolutePath());
        } 
        
        if ((!file.canWrite()) && (!readOnly)) {
            throw new IOException("Can't write to file: " + file.getAbsolutePath());
        } 

        this.file = file;
        this.readOnly = readOnly;
        this.offset = offset;
        this.len = len;
    }

    public RandomAccessFileBucket(SimpleFieldSet fs, PersistentFileTracker f) throws CannotCreateFromFieldSetException {
   		String tmp = fs.get("Filename");
   		if(tmp == null) throw new CannotCreateFromFieldSetException("No filename");
   		this.file = new File(tmp);
   		tmp = fs.get("Length");
   		if(tmp == null) throw new CannotCreateFromFieldSetException("No length");
   		try {
   			len = Long.parseLong(tmp);
   		} catch (NumberFormatException e) {
   			throw new CannotCreateFromFieldSetException("Corrupt length "+tmp, e);
   		}
   		tmp = fs.get("Offset");
   		if(tmp == null) throw new CannotCreateFromFieldSetException("No offset");
   		try {
   			offset = Long.parseLong(tmp);
   		} catch (NumberFormatException e) {
   			throw new CannotCreateFromFieldSetException("Corrupt offset "+tmp, e);
   		}
	}

    public static class Range {
        Range(long offset, long len) {
            this.offset = offset;
            this.len = len;
        }

        public long offset;
        public long len;
    }

    public final synchronized Range getRange() {
        return new Range(offset, len);
    }
    
    // hmmm make protected???
    public final synchronized boolean hasOpenStreams() {
        return streams.size() > 0;
    }

    // Wrap non-const members so we can tell
    // when code touches the Bucket after it
    // has been released.
    public synchronized InputStream getInputStream() throws IOException {
        if (isReleased()) {
            throw new IOException("Attempt to use a released RandomAccessFileBucket: " + getName() );
        }

        InputStream newIn = new RAInputStream(file.getAbsolutePath());
        streams.addElement(newIn);
        return newIn;
    }

    public synchronized OutputStream getOutputStream() throws IOException {
        if (isReleased()) {
            throw new IOException("Attempt to use a released RandomAccessBucket: " + getName() );
        }

        if (readOnly) {
            throw new IOException("Tried to write a read-only Bucket.");
        }

        OutputStream newOut = new RAOutputStream(file.getAbsolutePath());
        streams.addElement(newOut);
        return newOut;
    }

    public String getName() {
        return file.getAbsolutePath() + " [" + offset + ", " + 
            (offset + len - 1) + ']';
    }
        
    public synchronized void resetWrite() {
        if (isReleased()) {
            throw new RuntimeException("Attempt to use a released RandomAccessFileBucket: " + getName() );
        }
        // REDFLAG: implicit assumptions
        // 0) Bucket is only written to at a time.
        // 1) The output stream is closed before the
        //    next is open. Ouch. This may cause problems...
        localOffset = 0;
    }

    public long size() { return len; }

    public synchronized boolean release() {
        if (released) {
            return true;
        }

        // Force all open streams closed. 
        // Windows won't let us delete the file unless we
        // do this.
        for (int i =0; i < streams.size(); i++) {
            try {
                if (streams.elementAt(i) instanceof InputStream) {
                    ((InputStream)streams.elementAt(i)).close();

                    if(Logger.shouldLog(Logger.DEBUG, this))
                    	Logger.debug(this, "closed open InputStream !: " + 
                    			file.getAbsolutePath());
                }
                else if (streams.elementAt(i) instanceof OutputStream) {
                    ((OutputStream)streams.elementAt(i)).close();
                    if(Logger.shouldLog(Logger.DEBUG, this))
                    	Logger.debug(this, "closed open OutputStream !: " + 
                    			file.getAbsolutePath());
                }
            }
            catch (IOException ioe) {
            }
        }
        streams.removeAllElements();
        streams.trimToSize();
        // We don't delete anything because we don't own anything.
        released = true;
        return true;
    }

    public synchronized final boolean isReleased() { return released; }

    public void finalize() throws Throwable {
    	synchronized(this) {
    		if(released) return;
    	}
    	release();
    }

    // REDFLAG: RETEST
    // set blocks = -1 for until end.
    // last block may have length < blockSize
    public static Bucket[] segment(File file, int blockSize, 
                                   long offset, int blocks,  boolean readOnly) 
        throws IOException {
        
        if (!(file.exists() && file.canRead())) {
            throw new IOException("Can't read file: " + file.getAbsolutePath());
        } 
        
        if ((!file.canWrite()) && (!readOnly)) {
            throw new IOException("Can't write to file: " + file.getAbsolutePath());
        } 
        
        if ((offset < 0) || (offset >= file.length() - 1)) {
            throw new IllegalArgumentException("offset: " + offset);
        }

        long length = file.length() - offset;
        int nBlocks = (int) (length / blockSize);
        if ((length % blockSize) != 0) {
            nBlocks++;
        }        
        
        if (blocks == -1) {
            blocks = nBlocks;
        }
        else if ((blocks > nBlocks) || (blocks < 1)) {
            throw new IllegalArgumentException("blocks: " + blocks);
        } 

        Bucket[] ret = new Bucket[blocks];
        
        for (int i = 0; i < blocks; i++) {
            final long localOffset = i * blockSize * 1L + offset;
            int blockLen = blockSize;
            if (i == nBlocks - 1) {
                blockLen = (int) (length - (nBlocks - 1) * blockSize * 1L);
            }
            ret[i] = new RandomAccessFileBucket(file, localOffset, blockLen, readOnly);
        }
        
        return ret;
    }

    ////////////////////////////////////////////////////////////
    // InputStream and OutputStream implementations
    //
    private final static boolean vociferous = false;

    class RAInputStream extends InputStream  {
        public RAInputStream(String prefix) throws IOException {
            raf = new RandomAccessFile(file, "r");
            raf.seek(offset);
            println(" -- Created new InputStream [" + offset + 
                    ", " + (offset + len -1) + ']');
        }
        
        ////////////////////////////////////////////////////////////
        // FilterInput implementation

        private final int bytesLeft() throws IOException {
			synchronized (RandomAccessFileBucket.this) {
				return (int)(offset + len - raf.getFilePointer());
			}
        }

        public int read() throws java.io.IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".read()");
                checkValid();
                if (bytesLeft() < 1) {
                    return -1; // EOF
                } 
                return raf.read();
            }
        }
        
        public int read(byte[] bytes) throws java.io.IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".read(byte[])");
                checkValid();
                int nAvailable = bytesLeft();
                if (nAvailable < 1) {
                    return -1; // EOF
                } 
                if (nAvailable > bytes.length) {
                    nAvailable = bytes.length;
                }
                return raf.read(bytes, 0, nAvailable);
            }
        }
        
        public int read(byte[] bytes, int a, int b) throws java.io.IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".read(byte[], int, int)");
                checkValid();
                int nAvailable = bytesLeft();
                if (nAvailable < 1) {
                    return -1; // EOF
                } 
                if (nAvailable > b) {
                    nAvailable = b;
                }
                return raf.read(bytes, a, nAvailable);
            }
        }
        
        public long skip(long a) throws java.io.IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".skip(long)");
                checkValid();
                int nAvailable = bytesLeft();
                if (nAvailable < 1) {
                    return -1; // EOF
                } 
                if (nAvailable > a) {
                    nAvailable = (int)a;
                }

                return raf.skipBytes(nAvailable);
            }
        }
        
        public int available() throws java.io.IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".available()");
                checkValid();
                return bytesLeft();
            }
        }
        
        public void close() throws java.io.IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".close()");
                checkValid();       
                raf.close();
                if (streams.contains(RAInputStream.this)) {
                    streams.removeElement(RAInputStream.this);
                }
                streams.trimToSize();
            }
        }
        
        // LATER: support if really needed.
        public  void mark(int a) {
            // NOP
        }

        public void reset() {
            // NOP
        }
        
        public boolean markSupported() {
            return false;
        }

        private final void println(String text) {
            if (vociferous) {
                if(Logger.shouldLog(Logger.DEBUG, this))
                	Logger.debug(this, text);
            }
        }
        
        private final void checkValid() throws IOException {
			synchronized(RandomAccessFileBucket.this) {
				if (released) {
					throw new IOException("Attempt to use a released RandomAccessFileBucket: " + prefix);
				}
			}
        }

        ////////////////////////////////////////////////////////////
        private RandomAccessFile raf = null;
        private String prefix = "";
    }

    private class RAOutputStream extends OutputStream {
        public RAOutputStream(String pref) throws IOException {
            raf = new RandomAccessFile(file, "rw");
            raf.seek(offset + localOffset);
            println(" -- Created new OutputStream [" + offset + ", " 
                    + (offset + len -1) + ']');
        }
    
        ////////////////////////////////////////////////////////////
        // OutputStream implementation
        public void write(int b) throws IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".write(b)");
                checkValid();
                int nAvailable = bytesLeft();
                if (nAvailable < 1) {
                    throw new IOException("Attempt to write past end of Bucket.");
                }
                raf.write(b);
            }
        }
    
        public void write(byte[] buf) throws IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".write(buf)");
                checkValid();
                int nAvailable = bytesLeft();
                if (nAvailable < buf.length) {
                    throw new IOException("Attempt to write past end of Bucket.");
                }
                raf.write(buf);
            }
        }
    
        public void write(byte[] buf, int off, int len) throws IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".write(buf,off,len)");
                checkValid();
                int nAvailable = bytesLeft();
                if (nAvailable < len) {
                    throw new IOException("Attempt to write past end of Bucket.");
                }
                raf.write(buf, off, len);
            }
        }
    
        public void flush() throws IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".flush()");
                checkValid();
                // NOP? Bytes written immediately?
                // REDFLAG: double check.
            }
        }
    
        public void close() throws IOException {
            synchronized (RandomAccessFileBucket.this) {
                println(".close()");
                checkValid();
                if (streams.contains(RAOutputStream.this)) {
                    streams.removeElement(RAOutputStream.this);
                }
                streams.trimToSize();
                long added = raf.getFilePointer() - offset;
                if (added > 0) {
                    // To get proper append behavior.
                    localOffset = added;
                }
                
                raf.close();
            }
        }

        ////////////////////////////////////////////////////////////
        private void println(String text) {
            if (vociferous) {
                if(Logger.shouldLog(Logger.DEBUG, this))
                	Logger.debug(this, text);
            }
        }

        private final void checkValid() throws IOException {
			synchronized (RandomAccessFileBucket.this) {
				if (isReleased()) {
					throw new IOException("Attempt to use a released RandomAccessFileBucket: " + prefix);
				}
			}
        }
        private final int bytesLeft() throws IOException {
			synchronized (RandomAccessFileBucket.this) {
				return (int)(offset + len - raf.getFilePointer());
			}
        }

        private RandomAccessFile raf = null;
        private String prefix = "";

    }
    ////////////////////////////////////////////////////////////

    public synchronized boolean isReadOnly() {
    	return readOnly;
    }
    
    public synchronized void setReadOnly() {
    	readOnly = true;
    }

	public void free() {
		release();
	}

	public synchronized SimpleFieldSet toFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.putSingle("Type", "RandomAccessFileBucket");
		fs.putSingle("Filename", file.toString());
		fs.put("Offset", offset);
		fs.put("Length", len);
		return fs;
	}
}
