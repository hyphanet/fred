package freenet.support.io;

import java.io.IOException;

/** A RandomAccessThing which allows you to lock it open for a brief period to indicate that you are
 * using it and it would be a bad idea to close the pooled fd. Locking the RAF open does not provide
 * any concurrency guarantees but the implementation must guarantee to do the right thing, either
 * using a mutex or supporting concurrent writes.
 * @author toad
 */
public interface LockableRandomAccessThing extends RandomAccessThing {
	
    /** Keep the RAF open. Does not prevent others from writing to it. */
	public RAFLock lock() throws IOException;
	
	abstract class RAFLock {
	    
	    private boolean locked;
	    
	    RAFLock() {
	        locked = true;
	    }
	    
	    public final void unlock() {
	        synchronized(this) {
	            if(!locked)
	                throw new IllegalStateException("Already unlocked");
	            locked = false;
	        }
	        innerUnlock();
	    }
	    
	    protected abstract void innerUnlock();
	    
	}
	
}

