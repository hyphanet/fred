package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;

import freenet.client.async.ClientContext;

public class DelayedFreeRandomAccessThing implements LockableRandomAccessThing, Serializable {
    
    private static final long serialVersionUID = 1L;
    final LockableRandomAccessThing underlying;
    private boolean freed;
    private transient PersistentFileTracker factory;

    public DelayedFreeRandomAccessThing(LockableRandomAccessThing raf, PersistentFileTracker factory) {
        underlying = raf;
        this.factory = factory;
    }

    @Override
    public long size() throws IOException {
        return underlying.size();
    }

    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        synchronized(this) {
            if(freed) throw new IOException("Already freed");
        }
        underlying.pread(fileOffset, buf, bufOffset, length);
    }

    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        synchronized(this) {
            if(freed) throw new IOException("Already freed");
        }
        underlying.pwrite(fileOffset, buf, bufOffset, length);
    }

    @Override
    public void close() {
        synchronized(this) {
            if(freed) return;
        }
        underlying.close();
    }

    @Override
    public void free() {
        synchronized(this) {
            if(freed) return;
            this.factory.delayedFreeBucket(this);
            freed = true;
        }
    }

    @Override
    public RAFLock lockOpen() throws IOException {
        synchronized(this) {
            if(freed) throw new IOException("Already freed");
        }
        return underlying.lockOpen();
    }

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        this.factory = context.persistentBucketFactory;
        underlying.onResume(context);
    }
    
    static final int MAGIC = 0x3fb645de;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        underlying.storeTo(dos);
    }
    
    public DelayedFreeRandomAccessThing(DataInputStream dis, FilenameGenerator fg,
            PersistentFileTracker persistentFileTracker) throws IOException, StorageFormatException, ResumeFailedException {
        underlying = BucketTools.restoreRAFFrom(dis, fg, persistentFileTracker);
        factory = persistentFileTracker;
    }
    
    public boolean toFree() {
        return freed;
    }
    
    public LockableRandomAccessThing getUnderlying() {
        if(freed) return null;
        return underlying;
    }

    public void realFree() {
        underlying.free();
    }
    
}
