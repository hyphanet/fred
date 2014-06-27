package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Deque;

import freenet.support.Logger;

/** Random access files with a limited number of open files, using a pool. 
 * LOCKING OPTIMISATION: Contention on closables likely here. It's not clear how to avoid that, FIXME.
 * However, this is doing disk I/O (even if cached, system calls), so maybe it's not a big deal ... */
public class PooledRandomAccessFileWrapper implements LockableRandomAccessThing {
    
    private static int MAX_OPEN_FDS = 100;
    static int OPEN_FDS = 0;
    static final Deque<PooledRandomAccessFileWrapper> closables = new ArrayDeque<PooledRandomAccessFileWrapper>();
    
    public final File file;
    private final String mode;
    /** >0 means locked. We will wait until we get the lock if necessary, this is always accurate. 
     * LOCKING: Synchronized on closables (i.e. static, but not the class). */
    private int lockLevel;
    /** The actual RAF. Non-null only if open. LOCKING: Synchronized on (this).
     * LOCKING: Always take (this) last, i.e. after closables. */
    private RandomAccessFile raf;
    private final long length;
    private boolean closed;
    
    public PooledRandomAccessFileWrapper(File file, String mode, long forceLength) throws IOException {
        this.file = file;
        this.mode = mode;
        lockLevel = 0;
        // Check the parameters and get the length.
        // Also, unlock() adds to the closeables queue, which is essential.
        RAFLock lock = lockOpen();
        try {
            long currentLength = raf.length();
            if(forceLength >= 0) {
                // FIXME unix sparse files? We may need to preallocate space reliably, write errors mid file tend to break things badly as does out of disk space.
                raf.setLength(forceLength); 
                currentLength = forceLength;
            }
            this.length = currentLength;
            lock.unlock();
        } catch (IOException e) {
            synchronized(this) {
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
        if(fileOffset < 0) throw new IllegalArgumentException();
        RAFLock lock = lockOpen();
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
        if(fileOffset < 0) throw new IllegalArgumentException();
        RAFLock lock = lockOpen();
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
        synchronized(closables) {
            if(lockLevel != 0)
                throw new IllegalStateException("Must unlock first!");
            closed = true;
            // Essential to avoid memory leak!
            // Potentially slow but only happens on close(). Plus the size of closables is bounded anyway by the fd limit.
            closables.remove(this);
            closeRAF();
        }
    }

    @Override
    public RAFLock lockOpen() throws IOException {
        RAFLock lock = new RAFLock() {

            @Override
            protected void innerUnlock() {
                PooledRandomAccessFileWrapper.this.unlock();
            }
            
        };
        synchronized(closables) {
            while(true) {
                if(closed) throw new IOException("Already closed");
                if(raf != null) {
                    lockLevel++; // Already open, may or may not be already locked.
                    return lock;
                } else if(OPEN_FDS < MAX_OPEN_FDS) {
                    lockLevel++;
                    OPEN_FDS++;
                    try {
                        raf = new RandomAccessFile(file, mode);
                    } catch (IOException e) {
                        unlock();
                        throw e;
                    }
                    return lock;
                } else {
                    PooledRandomAccessFileWrapper closable = closables.pollFirst();
                    if(closable != null) {
                        closable.closeRAF();
                        continue;
                    }
                    try {
                        closables.wait();
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
        }
    }
    
    /** Should be synchronized on class already */
    private void closeRAF() {
        if(!closed && lockLevel != 0) throw new IllegalStateException();
        if(raf == null) return;
        try {
            raf.close();
        } catch (IOException e) {
            Logger.error(this, "Error closing "+this+" : "+e, e);
        }
        raf = null;
        OPEN_FDS--;
    }

    private void unlock() {
        synchronized(closables) {
            lockLevel--;
            if(lockLevel > 0) return;
            closables.addLast(this);
            closables.notify();
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
    
    /** Set the size of the fd pool */
    public static void setMaxFDs(int max) {
        synchronized(closables) {
            if(max <= 0) throw new IllegalArgumentException();
            MAX_OPEN_FDS = max;
        }
    }

    /** How many fd's are open right now? Mainly for tests but also for stats. */
    public static int getOpenFDs() {
        return OPEN_FDS;
    }
    
    static int getClosableFDs() {
        synchronized(closables) {
            return closables.size();
        }
    }
    
    boolean isOpen() {
        synchronized(closables) {
            return raf != null;
        }
    }
    
    boolean isLocked() {
        synchronized(closables) {
            return lockLevel != 0;
        }
    }

}
