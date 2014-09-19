package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.client.async.ClientContext;
import freenet.crypt.MasterSecret;

public class TrivialPaddedRandomAccessThing implements LockableRandomAccessThing {
    
    final LockableRandomAccessThing raf;
    final long realSize;

    public TrivialPaddedRandomAccessThing(LockableRandomAccessThing raf, long realSize) {
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
    
    public TrivialPaddedRandomAccessThing(DataInputStream dis, FilenameGenerator fg,
            PersistentFileTracker persistentFileTracker, MasterSecret masterSecret) throws ResumeFailedException, IOException, StorageFormatException {
        realSize = dis.readLong();
        if(realSize < 0) throw new StorageFormatException("Negative length");
        raf = BucketTools.restoreRAFFrom(dis, fg, persistentFileTracker, masterSecret);
        if(realSize > raf.size())
            throw new ResumeFailedException("Padded file is smaller than expected length");
    }

}
