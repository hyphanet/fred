package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Deque;

import freenet.support.Logger;

public class PooledRandomAccessFileWrapper implements LockableRandomAccessThing {
    
    static final int MAX_OPEN_FDS = 100;
    static int OPEN_FDS = 0;
    static final Deque<PooledRandomAccessFileWrapper> closables = new ArrayDeque<PooledRandomAccessFileWrapper>();
    
    public final File file;
    private final String mode;
    /** >0 means locked. We will wait until we get the lock if necessary, this is always accurate. 
     * LOCKING: Synchronized on class. */
    private int lockLevel;
    /** The actual RAF. Non-null only if open. LOCKING: Synchronized on (this). */
    private RandomAccessFile raf;
    private final long length;
    private boolean closed;
    
    public PooledRandomAccessFileWrapper(File file, String mode) throws IOException {
        this.file = file;
        this.mode = mode;
        lockLevel = 0;
        // Check the parameters and get the length.
        RAFLock lock = lock();
        try {
            length = raf.length();
            lock.unlock();
        } catch (IOException e) {
            synchronized(PooledRandomAccessFileWrapper.class) {
                raf.close();
                raf = null;
            }
            throw e;
        }
    }

    @Override
    public long size() throws IOException {
        return length;
    }

    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        RAFLock lock = lock();
        try {
            // FIXME Use NIO! This is absurd!
            synchronized(this) {
                raf.seek(fileOffset);
                raf.readFully(buf, bufOffset, length);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        RAFLock lock = lock();
        try {
            if(fileOffset + length > this.length)
                throw new IOException("Length limit exceeded");
            // FIXME Use NIO (which has proper pwrite, with concurrency)! This is absurd!
            synchronized(this) {
                raf.seek(fileOffset);
                raf.write(buf, bufOffset, length);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        synchronized(PooledRandomAccessFileWrapper.class) {
            if(lockLevel != 0)
                throw new IllegalStateException("Must unlock first!");
            closed = true;
            closeRAF();
        }
    }

    @Override
    public RAFLock lock() throws IOException {
        RAFLock lock = new RAFLock() {

            @Override
            protected void innerUnlock() {
                unlock();
            }
            
        };
        synchronized(PooledRandomAccessFileWrapper.class) {
            if(closed) throw new IOException("Already closed");
            if(lockLevel > 0) {
                // Already locked. Ok.
                lockLevel++;
                return lock;
            } else {
                // Wait for space.
                while(true) {
                    if(OPEN_FDS < MAX_OPEN_FDS) break;
                    PooledRandomAccessFileWrapper closable = closables.pollFirst();
                    if(closable != null) {
                        closable.closeRAF();
                        continue;
                    }
                    try {
                        PooledRandomAccessFileWrapper.class.wait();
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
                OPEN_FDS++;
                lockLevel++;
            }
            if(raf != null) return lock;
            try {
                raf = new RandomAccessFile(file, mode);
            } catch (IOException e) {
                unlock();
                throw e;
            }
            return lock;
        }
    }
    
    /** Should be synchronized on class already */
    private void closeRAF() {
        try {
            raf.close();
        } catch (IOException e) {
            Logger.error(this, "Error closing "+this+" : "+e, e);
        }
        raf = null;
        OPEN_FDS--;
    }

    private void unlock() {
        synchronized(PooledRandomAccessFileWrapper.class) {
            lockLevel--;
            if(lockLevel > 0) return;
            closables.addLast(this);
            PooledRandomAccessFileWrapper.class.notify();
        }
    }

    @Override
    public void free() {
        close();
        try {
            FileUtil.secureDelete(file);
        } catch (IOException e) {
            Logger.error(this, "Unable to delete "+file+" : "+e, e);
            System.err.println("Unable to delete temporary file "+file);
        }
    }

}
