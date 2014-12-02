package freenet.support.io;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.client.async.ClientContext;
import freenet.support.api.LockableRandomAccessBuffer;

public class NullRandomAccessBuffer implements LockableRandomAccessBuffer {
    
    final long length;

    public NullRandomAccessBuffer(long length) {
        this.length = length;
    }

    @Override
    public long size() {
        return length;
    }

    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        for(int i=0;i<length;i++)
            buf[bufOffset+i] = 0;
    }

    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        // Do nothing.
    }

    @Override
    public void close() {
        // Do nothing.
    }

    @Override
    public void free() {
        // Do nothing.
    }

    @Override
    public RAFLock lockOpen() throws IOException {
        return new RAFLock() {

            @Override
            protected void innerUnlock() {
                // Do nothing.
            }
            
        };
    }

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public int hashCode() {
        return 0;
    }
    
    public boolean equals(Object o) {
        return o.getClass() == getClass();
    }

}
