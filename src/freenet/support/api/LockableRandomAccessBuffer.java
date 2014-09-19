package freenet.support.api;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.client.async.ClientContext;
import freenet.support.io.ResumeFailedException;

/** A RandomAccessBuffer which allows you to lock it open for a brief period to indicate that you are
 * using it and it would be a bad idea to close the pooled fd. Locking the RAF open does not provide
 * any concurrency guarantees but the implementation must guarantee to do the right thing, either
 * using a mutex or supporting concurrent writes. Also has methods for persisting itself to a
 * DataOutputStream. Implementations must register with BucketTools.restoreRAFFrom().
 * @author toad
 */
public interface LockableRandomAccessBuffer extends RandomAccessBuffer {
	
    /** Keep the RAF open. Does not prevent others from writing to it. Will block until a slot is available 
     * if necessary. Hence can deadlock. */
	public RAFLock lockOpen() throws IOException;
	
	public abstract class RAFLock {
	    
	    private boolean locked;
	    
	    public RAFLock() {
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
     * for recovering in emergencies, should be versioned if necessary. To make this work, write
     * a fixed, unique integer magic value for the class, and add a clause to 
     * BucketTools.restoreRAFFrom().
     * @throws IOException */
    public void storeTo(DataOutputStream dos) throws IOException;
    
    /** Must reimplement equals(). Sometimes we will need to compare two RAFs to see if they 
     * represent the same stored object, notably during resuming a splitfile insert. */
    @Override
    public abstract boolean equals(Object o);
    
    /** Must reimplement hashCode() if we change equals(). */
    @Override
    public abstract int hashCode();
	
}

