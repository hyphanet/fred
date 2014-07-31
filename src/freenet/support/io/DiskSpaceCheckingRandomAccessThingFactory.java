package freenet.support.io;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DiskSpaceCheckingRandomAccessThingFactory implements LockableRandomAccessThingFactory {

    private final LockableRandomAccessThingFactory underlying;
    private final File dir;
    private volatile long minDiskSpace;
    
    /** LOCKING: We synchronize throughout the whole operation to prevent fragmentation and to have
     * an accurate free space estimate. FIXME ideally this would be per-filesystem. It might be 
     * possible to get that information from Java (1.7) via java.nio.file. */
    private static final Lock lock = new ReentrantLock(true);
    
    public DiskSpaceCheckingRandomAccessThingFactory(LockableRandomAccessThingFactory underlying, 
            File dir, long minDiskSpace) {
        this.underlying = underlying;
        this.dir = dir;
        this.minDiskSpace = minDiskSpace;
    }
    
    public void setMinDiskSpace(long min) {
        if(min < 0) throw new IllegalArgumentException();
        this.minDiskSpace = min;
    }
    
    class InsufficientSpaceException extends IOException {
        
    }

    
    @Override
    public LockableRandomAccessThing makeRAF(long size) throws IOException {
        lock.lock();
        try {
            if(dir.getFreeSpace() > size + minDiskSpace)
                return underlying.makeRAF(size);
            else
                throw new InsufficientSpaceException();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public synchronized LockableRandomAccessThing makeRAF(byte[] initialContents, int offset, int size)
            throws IOException {
        lock.lock();
        try {
            if(dir.getFreeSpace() > size + minDiskSpace)
                return underlying.makeRAF(size);
            else
                throw new InsufficientSpaceException();
        } finally {
            lock.unlock();
        }
    }

}
