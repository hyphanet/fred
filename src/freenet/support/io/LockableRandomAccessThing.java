package freenet.support.io;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.client.async.ClientContext;

/** A RandomAccessThing which allows you to lock it open for a brief period to indicate that you are
 * using it and it would be a bad idea to close the pooled fd. Locking the RAF open does not provide
 * any concurrency guarantees but the implementation must guarantee to do the right thing, either
 * using a mutex or supporting concurrent writes.
 * @author toad
 */
public interface LockableRandomAccessThing extends RandomAccessThing {
	
    /** Keep the RAF open. Does not prevent others from writing to it. Will block until a slot is available 
     * if necessary. Hence can deadlock. */
	public RAFLock lockOpen() throws IOException;
	
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

	/** Called on resuming, i.e. after serialization. Use to e.g. register with the list of 
	 * temporary files. */
    public void onResume(ClientContext context) throws ResumeFailedException;

    /** Write enough data to reconstruct the Bucket, or throw UnsupportedOperationException. Used
     * for recovering in emergencies, should be versioned if necessary. 
     * @throws IOException */
    public void storeTo(DataOutputStream dos) throws IOException;
	
}

