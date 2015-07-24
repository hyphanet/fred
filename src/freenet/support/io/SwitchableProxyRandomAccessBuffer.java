package freenet.support.io;

import java.io.IOException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.support.api.LockableRandomAccessBuffer;

/** Proxy LockableRandomAccessBuffer allowing changing the pointer to the underlying RAT. */
abstract class SwitchableProxyRandomAccessBuffer implements LockableRandomAccessBuffer {

    /** Size of the temporary storage. Note that this may be smaller than underlying.size(),
     * and will be enforced before passing requests on. */
    final long size;
    /** Underlying temporary storage. May change! Will be thread-safe as with all RAT's.
     * Current implementations just lock all writes/reads. */
    private LockableRandomAccessBuffer underlying;
    /** Number of currently valid RAFLock's on this RAF. We centralise this here so that we
     * only have to take a single new lock when migrating. */
    private int lockOpenCount;
    /** Lock we took on the underlying when the first caller called lockOpen(). */
    private RAFLock underlyingLock;
    private boolean closed;
    /** Read/write lock for the pointer to underlying and lockOpenCount. That is, we take a 
     * write lock when we want to change the underlying pointer or other mutable fields, e.g. 
     * during migration or freeing the data, and a read lock for any other operation, hence we 
     * ensure that there is no other I/O going on during a migration. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    SwitchableProxyRandomAccessBuffer(LockableRandomAccessBuffer initialWrap, long size) throws IOException {
        this.underlying = initialWrap;
        this.size = size;
        if(underlying.size() < size) throw new IOException("Underlying must be >= size given");
    }
    
    @Override
    public long size() {
        return size;
    }

    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length)
            throws IOException {
        if(fileOffset < 0) throw new IllegalArgumentException();
        if(fileOffset+length > size) throw new IOException("Tried to read past end of file");
        try {
            lock.readLock().lock();
            if(underlying == null || closed) throw new IOException("Already closed");
            underlying.pread(fileOffset, buf, bufOffset, length);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length)
            throws IOException {
        if(fileOffset < 0) throw new IllegalArgumentException();
        if(fileOffset+length > size) throw new IOException("Tried to write past end of file");
        try {
            lock.readLock().lock();
            if(underlying == null || closed) throw new IOException("Already closed");
            underlying.pwrite(fileOffset, buf, bufOffset, length);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        try {
            lock.writeLock().lock();
            if(underlying == null) return;
            if(closed) return;
            closed = true;
            underlying.close();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void free() {
        innerFree();
    }
    
    /** @return True unless the buffer has already been freed. */
    protected boolean innerFree() {
        try {
            // Write lock as we're going to change the underlying pointer.
            lock.writeLock().lock();
            closed = true; // Effectively ...
            if(underlying == null) return false;
            underlying.free();
            underlying = null;
        }  finally {
            lock.writeLock().unlock();
        }
        afterFreeUnderlying();
        return true;
    }
    
    public boolean hasBeenFreed() {
        try {
            lock.readLock().lock();
            return underlying == null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Called after freeing the underlying storage. That includes when migrating, not just when
     * free() is called! */ 
    protected void afterFreeUnderlying() {
        // Do nothing.
    }

    @Override
    public RAFLock lockOpen() throws IOException {
        try {
            lock.writeLock().lock();
            if(closed || underlying == null) throw new IOException("Already closed");
            RAFLock lock = new RAFLock() {

                @Override
                protected void innerUnlock() {
                    externalUnlock();
               }
                
            };
            lockOpenCount++;
            if(lockOpenCount == 1) {
                assert(underlyingLock == null);
                underlyingLock = underlying.lockOpen();
            }
            return lock;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Called when an external lock-open RAFLock is closed. */
    protected void externalUnlock() {
        try {
            lock.writeLock().lock();
            lockOpenCount--;
            if(lockOpenCount == 0) {
                underlyingLock.unlock();
                underlyingLock = null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /** Migrate from one underlying LockableRandomAccessBuffer to another. */
    protected final void migrate() throws IOException {
        try {
            lock.writeLock().lock();
            if(closed) return;
            if(underlying == null) throw new IOException("Already freed");
            LockableRandomAccessBuffer successor = innerMigrate(underlying);
            if(successor == null) throw new NullPointerException();
            RAFLock newLock = null;
            if(lockOpenCount > 0) {
                try {
                    newLock = successor.lockOpen();
                } catch (IOException e) {
                    successor.close();
                    successor.free();
                    throw e;
                }
            }
            if(lockOpenCount > 0)
                underlyingLock.unlock();
            underlying.close();
            underlying.free();
            underlying = successor;
            underlyingLock = newLock;
        } finally {
            lock.writeLock().unlock();
        }
        afterFreeUnderlying();
    }
    
    /** Create a new LockableRandomAccessBuffer containing the same data as the current underlying. 
     * @throws IOException If the migrate failed. */
    protected abstract LockableRandomAccessBuffer innerMigrate(LockableRandomAccessBuffer underlying) throws IOException;
    
    /** For unit tests only */
    synchronized LockableRandomAccessBuffer getUnderlying() {
        return underlying;
    }
    
    // Default hashCode() and equals() i.e. comparison by identity are correct for this type.
    
}
