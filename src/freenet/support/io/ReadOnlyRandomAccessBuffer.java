package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.client.async.ClientContext;
import freenet.crypt.MasterSecret;
import freenet.support.api.LockableRandomAccessBuffer;

public class ReadOnlyRandomAccessBuffer implements LockableRandomAccessBuffer {
    
    private final LockableRandomAccessBuffer underlying;

    public ReadOnlyRandomAccessBuffer(LockableRandomAccessBuffer underlying) {
        this.underlying = underlying;
    }

    public ReadOnlyRandomAccessBuffer(DataInputStream dis, FilenameGenerator fg, 
            PersistentFileTracker persistentFileTracker, MasterSecret masterSecret) 
    throws IOException, StorageFormatException, ResumeFailedException {
        // Caller has already read magic
        this.underlying = BucketTools.restoreRAFFrom(dis, fg, persistentFileTracker, masterSecret);
    }

    @Override
    public long size() {
        return underlying.size();
    }

    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        underlying.pread(fileOffset, buf, bufOffset, length);
    }

    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        throw new IOException("Read only");
    }

    @Override
    public void close() {
        underlying.close();
    }

    @Override
    public void free() {
        underlying.free();
    }

    @Override
    public RAFLock lockOpen() throws IOException {
        return underlying.lockOpen();
    }

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        underlying.onResume(context);
    }
    
    final static int MAGIC = 0x648d24da;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        underlying.storeTo(dos);
    }

    @Override
    public int hashCode() {
        return underlying.hashCode();
    }

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
        ReadOnlyRandomAccessBuffer other = (ReadOnlyRandomAccessBuffer) obj;
        return underlying.equals(other.underlying);
    }

}
