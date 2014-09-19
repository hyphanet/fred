package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.crypt.MasterSecret;
import freenet.support.api.LockableRandomAccessBuffer;

public class PaddedRandomAccessBuffer implements LockableRandomAccessBuffer, Serializable {
    
    private static final long serialVersionUID = 1L;
    final LockableRandomAccessBuffer raf;
    final long realSize;

    public PaddedRandomAccessBuffer(LockableRandomAccessBuffer raf, long realSize) {
        this.raf = raf;
        this.realSize = realSize;
    }

    @Override
    public long size() {
        return realSize;
    }

    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        if(fileOffset + length > realSize)
            throw new IOException("Length limit exceeded");
        raf.pread(fileOffset, buf, bufOffset, length);
    }

    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        if(fileOffset + length > realSize)
            throw new IOException("Length limit exceeded");
        raf.pwrite(fileOffset, buf, bufOffset, length);
    }

    @Override
    public void close() {
        raf.close();
    }

    @Override
    public void free() {
        raf.free();
    }

    @Override
    public RAFLock lockOpen() throws IOException {
        return raf.lockOpen();
    }

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        raf.onResume(context);
    }
    
    static final int MAGIC = 0x1eaaf330;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeLong(realSize);
        raf.storeTo(dos);
    }
    
    public PaddedRandomAccessBuffer(DataInputStream dis, FilenameGenerator fg,
            PersistentFileTracker persistentFileTracker, MasterSecret masterSecret) throws ResumeFailedException, IOException, StorageFormatException {
        realSize = dis.readLong();
        if(realSize < 0) throw new StorageFormatException("Negative length");
        raf = BucketTools.restoreRAFFrom(dis, fg, persistentFileTracker, masterSecret);
        if(realSize > raf.size())
            throw new ResumeFailedException("Padded file is smaller than expected length");
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + raf.hashCode();
        result = prime * result + (int) (realSize ^ (realSize >>> 32));
        return result;
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
        PaddedRandomAccessBuffer other = (PaddedRandomAccessBuffer) obj;
        if (!raf.equals(other.raf)) {
            return false;
        }
        if (realSize != other.realSize) {
            return false;
        }
        return true;
    }

}
