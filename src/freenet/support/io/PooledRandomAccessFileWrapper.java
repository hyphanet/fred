package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Random;

import freenet.client.async.ClientContext;
import freenet.support.Logger;
import freenet.support.math.MersenneTwister;

/** Random access files with a limited number of open files, using a pool. 
 * LOCKING OPTIMISATION: Contention on closables likely here. It's not clear how to avoid that, FIXME.
 * However, this is doing disk I/O (even if cached, system calls), so maybe it's not a big deal ... */
public class PooledRandomAccessFileWrapper implements LockableRandomAccessThing, Serializable {
    
    private static int MAX_OPEN_FDS = 100;
    static int OPEN_FDS = 0;
    static final LinkedHashSet<PooledRandomAccessFileWrapper> closables = new LinkedHashSet<PooledRandomAccessFileWrapper>();
    
    public final File file;
    private final String mode;
    /** >0 means locked. We will wait until we get the lock if necessary, this is always accurate. 
     * LOCKING: Synchronized on closables (i.e. static, but not the class). */
    private int lockLevel;
    /** The actual RAF. Non-null only if open. LOCKING: Synchronized on (this).
     * LOCKING: Always take (this) last, i.e. after closables. */
    private transient RandomAccessFile raf;
    private final long length;
    private boolean closed;
    private final boolean persistentTemp;
    
    public PooledRandomAccessFileWrapper(File file, String mode, long forceLength, Random seedRandom, boolean persistentTemp) throws IOException {
        this.file = file;
        this.mode = mode;
        this.persistentTemp = persistentTemp;
        lockLevel = 0;
        // Check the parameters and get the length.
        // Also, unlock() adds to the closeables queue, which is essential.
        RAFLock lock = lockOpen();
        try {
            long currentLength = raf.length();
            if(forceLength >= 0) {
                // Preallocate space. We want predictable disk usage, not minimal disk usage, especially for downloads.
                raf.seek(0);
                MersenneTwister mt = null;
                if(seedRandom != null)
                    mt = new MersenneTwister(seedRandom.nextLong());
                byte[] buf = new byte[4096];
                for(long l = 0; l < forceLength; l+=4096) {
                    if(mt != null)
                        mt.nextBytes(buf);
                    int maxWrite = (int)Math.min(4096, forceLength - l);
                    raf.write(buf, 0, maxWrite);
                }
                assert(raf.getFilePointer() == forceLength);
                assert(raf.length() == forceLength);
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

    public PooledRandomAccessFileWrapper(File file, String mode, byte[] initialContents,
            int offset, int size, boolean persistentTemp) throws IOException {
        this.file = file;
        this.mode = mode;
        this.length = size;
        this.persistentTemp = persistentTemp;
        lockLevel = 0;
        RAFLock lock = lockOpen();
        try {
            raf.write(initialContents, offset, size);
            lock.unlock();
        } catch (IOException e) {
            synchronized(this) {
                raf.close();
                raf = null;
            }
            throw e;
        }
    }
    
    protected PooledRandomAccessFileWrapper() {
        // For serialization.
        file = null;
        mode = null;
        length = 0;
        persistentTemp = false;
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
                        // Don't call unlock(), don't want to add to closables.
                        lockLevel--;
                        OPEN_FDS--;
                        throw e;
                    }
                    return lock;
                } else {
                    PooledRandomAccessFileWrapper closable = pollFirstClosable();
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
    
    private PooledRandomAccessFileWrapper pollFirstClosable() {
        synchronized(closables) {
            Iterator<PooledRandomAccessFileWrapper> it = closables.iterator();
            if (it.hasNext()) {
                PooledRandomAccessFileWrapper first = it.next();
                it.remove();
                return first;
            }
            return null;
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
            closables.add(this);
            closables.notify();
        }
    }

    @Override
    public void free() {
        close();
        file.delete();
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

    @Override
    public void onResume(ClientContext context) {
        if(persistentTemp) {
            context.persistentFileTracker.register(file);
        } else
            throw new UnsupportedOperationException(); // Not persistent.
    }

}
