package freenet.support.io;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.client.async.ClientContext;
import freenet.support.api.LockableRandomAccessBuffer;

public class BarrierRandomAccessBuffer implements LockableRandomAccessBuffer {
    
    private final LockableRandomAccessBuffer underlying;
    private boolean proceed;
    private int waiting;
    
    public BarrierRandomAccessBuffer(LockableRandomAccessBuffer underlying) {
        this.underlying = underlying;
        proceed = true;
    }
    
    @Override
    public long size() {
        return underlying.size();
    }
    
    /** Wait until some threads are waiting for the proceed thread. */
    public void waitForWaiting() {
        synchronized(this) {
            if(proceed) throw new IllegalArgumentException();
            while(waiting == 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }

    /** Wait until the proceed flag is set. */
    private void waitForClear() {
        synchronized(this) {
            waiting++;
            if(waiting == 1)
                notifyAll();
            while(!proceed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
            waiting--;
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
        BarrierRandomAccessBuffer other = (BarrierRandomAccessBuffer) obj;
        return underlying.equals(other.underlying);
    }
    
}
