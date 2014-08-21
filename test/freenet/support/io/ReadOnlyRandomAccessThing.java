package freenet.support.io;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.client.async.ClientContext;

public class ReadOnlyRandomAccessThing implements LockableRandomAccessThing {
    
    private final LockableRandomAccessThing underlying;

    public ReadOnlyRandomAccessThing(LockableRandomAccessThing underlying) {
        this.underlying = underlying;
    }

    @Override
    public long size() throws IOException {
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
        throw new UnsupportedOperationException();
    }

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        throw new UnsupportedOperationException();
    }

}
