package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.crypt.MasterSecret;
import freenet.support.api.LockableRandomAccessBuffer;

public class DelayedFreeRandomAccessBuffer implements LockableRandomAccessBuffer, Serializable, DelayedFree {
    
    private static final long serialVersionUID = 1L;
    final LockableRandomAccessBuffer underlying;
    private boolean freed;
    private transient PersistentFileTracker factory;
    private transient long createdCommitID;

    public DelayedFreeRandomAccessBuffer(LockableRandomAccessBuffer raf, PersistentFileTracker factory) {
        underlying = raf;
        this.createdCommitID = factory.commitID();
        this.factory = factory;
    }

    @Override
    public long size() {
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
            freed = true;
        }
        this.factory.delayedFree(this, createdCommitID);
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
    
    public DelayedFreeRandomAccessBuffer(DataInputStream dis, FilenameGenerator fg,
            PersistentFileTracker persistentFileTracker, MasterSecret masterSecret) 
    throws IOException, StorageFormatException, ResumeFailedException {
        underlying = BucketTools.restoreRAFFrom(dis, fg, persistentFileTracker, masterSecret);
        factory = persistentFileTracker;
    }
    
    @Override
    public boolean toFree() {
        return freed;
    }
    
    public LockableRandomAccessBuffer getUnderlying() {
        if(freed) return null;
        return underlying;
    }

    @Override
    public void realFree() {
        underlying.free();
    }

    @Override
    public int hashCode() {
        return underlying.hashCode();
    }

    /** Two DelayedFreeBucket's for the same underlying can only happen on resume, in which case
     * we DO want them to compare as equal. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DelayedFreeRandomAccessBuffer other = (DelayedFreeRandomAccessBuffer) obj;
        return underlying.equals(other.underlying);
    }
    
}
