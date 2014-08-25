package freenet.support.io;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.client.async.ClientContext;
import freenet.support.io.LockableRandomAccessThing;

public class BarrierRandomAccessThing implements LockableRandomAccessThing {
    
    private final LockableRandomAccessThing underlying;
    private boolean proceed;
    
    public BarrierRandomAccessThing(LockableRandomAccessThing underlying) {
        this.underlying = underlying;
        proceed = true;
    }
    
    @Override
    public long size() throws IOException {
        return underlying.size();
    }

    private void waitForClear() {
        synchronized(this) {
            while(!proceed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }
    
    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        waitForClear();
        underlying.pread(fileOffset, buf, bufOffset, length);
    }
    
    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        waitForClear();
        underlying.pwrite(fileOffset, buf, bufOffset, length);
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
    
    public synchronized void proceed() {
        proceed = true;
        notifyAll();
    }
    
    public synchronized void pause() {
        proceed = false;
    }

}
