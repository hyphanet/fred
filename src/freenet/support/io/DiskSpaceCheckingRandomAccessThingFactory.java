package freenet.support.io;

import java.io.File;
import java.io.IOException;

public class DiskSpaceCheckingRandomAccessThingFactory implements LockableRandomAccessThingFactory {

    private final LockableRandomAccessThingFactory underlying;
    private final File dir;
    private volatile long minDiskSpace;
    
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

    // LOCKING: We synchronize throughout the whole operation to prevent fragmentation and to have
    // an accurate free space estimate.
    
    @Override
    public synchronized LockableRandomAccessThing makeRAF(long size) throws IOException {
        if(dir.getFreeSpace() > size + minDiskSpace)
            return underlying.makeRAF(size);
        else
            throw new InsufficientSpaceException();
    }

    @Override
    public synchronized LockableRandomAccessThing makeRAF(byte[] initialContents, int offset, int size)
            throws IOException {
        if(dir.getFreeSpace() > size + minDiskSpace)
            return underlying.makeRAF(size);
        else
            throw new InsufficientSpaceException();
    }

}
