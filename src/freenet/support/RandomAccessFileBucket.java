// REDFLAG: test and javadoc
package freenet.support;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Vector;

/**
 * Bucket implementation that can efficiently access any arbitrary byte-range
 * of a file.
 *
 **/
public class RandomAccessFileBucket implements Bucket {

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
        setRange(offset, len);
    }

    public synchronized void setRange(long offset, long len) throws IOException {
        if (isReleased()) {
            throw new IOException("Attempt to use a released RandomAccessFileBucket: " + getName() );
        }

        if (streams.size() > 0) {
            throw new IllegalStateException("Can't reset range.  There are open streams.");
        }
        if ((offset < 0) || (len < 0) || (offset + len > file.length())) {
            throw new IllegalArgumentException("Bad range arguments.");
        }
        this.offset = offset;
        this.len = len;
        localOffset = 0;
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

        InputStream newIn = new RAInputStream(this, file.getAbsolutePath());
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

        OutputStream newOut = new RAOutputStream(this, file.getAbsolutePath());
        streams.addElement(newOut);
        return newOut;
    }

    public String getName() {
        return file.getAbsolutePath() + " [" + offset + ", " + 
            (offset + len - 1) + "]";
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

                    Logger.debug(this, "closed open InputStream !: " + 
                               file.getAbsolutePath());
                }
                else if (streams.elementAt(i) instanceof OutputStream) {
                    ((OutputStream)streams.elementAt(i)).close();
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
        if (!released) {
            release();
        }
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
            final long localOffset = i * blockSize + offset;
            int blockLen = blockSize;
            if (i == nBlocks - 1) {
                blockLen = (int) (length - (nBlocks - 1) * blockSize);
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
        public RAInputStream(RandomAccessFileBucket rafb, String prefix) throws IOException {
            this.rafb = rafb;
            raf = new RandomAccessFile(rafb.file, "r");
            raf.seek(offset);
            println(" -- Created new InputStream [" + rafb.offset + 
                    ", " + (rafb.offset + rafb.len -1) + "]" );
        }
        
        ////////////////////////////////////////////////////////////
        // FilterInput implementation

        private final int bytesLeft() throws IOException {
            return (int)(rafb.offset + rafb.len - raf.getFilePointer()); 
        }

        public int read() throws java.io.IOException {
            synchronized (rafb) {
                println(".read()");
                checkValid();
                if (bytesLeft() < 1) {
                    return -1; // EOF
                } 
                return raf.read();
            }
        }
        
        public int read(byte[] bytes) throws java.io.IOException {
            synchronized (rafb) {
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
            synchronized (rafb) {
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
            synchronized (rafb) {
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
            synchronized (rafb) {
                println(".available()");
                checkValid();
                return bytesLeft();
            }
        }
        
        public void close() throws java.io.IOException {
            synchronized (rafb) {
                println(".close()");
                checkValid();       
                raf.close();
                if (rafb.streams.contains(RAInputStream.this)) {
                    rafb.streams.removeElement(RAInputStream.this);
                }
		rafb.streams.trimToSize();
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
                Logger.debug(this, text);
            }
        }
        
        private final void checkValid() throws IOException {
            if (rafb.released) {
                throw new IOException("Attempt to use a released RandomAccessFileBucket: " + prefix);
            }
        }

        ////////////////////////////////////////////////////////////
        private RandomAccessFileBucket rafb = null;
        private RandomAccessFile raf = null;
        private String prefix = "";
    }

    private class RAOutputStream extends OutputStream {
        public RAOutputStream(RandomAccessFileBucket rafb, String pref) throws IOException {
            this.rafb = rafb;
            raf = new RandomAccessFile(rafb.file, "rw");
            raf.seek(rafb.offset + rafb.localOffset);
            println(" -- Created new OutputStream [" + rafb.offset + ", " 
                    + (rafb.offset + rafb.len -1) + "]" );
        }
    
        ////////////////////////////////////////////////////////////
        // OutputStream implementation
        public void write(int b) throws IOException {
            synchronized (rafb) {
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
            synchronized (rafb) {
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
            synchronized (rafb) {
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
            synchronized (rafb) {
                println(".flush()");
                checkValid();
                // NOP? Bytes written immediately?
                // REDFLAG: double check.
            }
        }
    
        public void close() throws IOException {
            synchronized (rafb) {
                println(".close()");
                checkValid();
                if (rafb.streams.contains(RAOutputStream.this)) {
                    rafb.streams.removeElement(RAOutputStream.this);
                }
		rafb.streams.trimToSize();
                long added = raf.getFilePointer() - rafb.offset;
                if (added > 0) {
                    // To get proper append behavior.
                    rafb.localOffset = added;
                }
                
                raf.close();
            }
        }

        ////////////////////////////////////////////////////////////
        private void println(String text) {
            if (vociferous) {
                Logger.debug(this, text);
            }
        }

        private final void checkValid() throws IOException {
            if (rafb.isReleased()) {
                throw new IOException("Attempt to use a released RandomAccessFileBucket: " + prefix);
            }
        }
        private final int bytesLeft() throws IOException {
            return (int)(rafb.offset + rafb.len - raf.getFilePointer()); 
        }

        private RandomAccessFileBucket rafb = null;
        private RandomAccessFile raf = null;
        private String prefix = "";

    }
    ////////////////////////////////////////////////////////////

    private File file = null;
    private long offset = -1;
    private long localOffset = 0;
    private long len = -1;
    private boolean readOnly = false;
    private boolean released = false;
    private Vector streams = new Vector();
    
    public boolean isReadOnly() {
    	return readOnly;
    }
    
    public void setReadOnly() {
    	readOnly = true;
    }

	public void free() {
		release();
	}
}
