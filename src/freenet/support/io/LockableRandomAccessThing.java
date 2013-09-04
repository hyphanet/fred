package freenet.support.io;

/** A RandomAccessThing which allows you to lock it open for a brief period to indicate that you are
 * using it and it would be a bad idea to close the pooled fd. Locking the RAF open does not provide
 * any concurrency guarantees but the implementation must guarantee to do the right thing, either
 * using a mutex or supporting concurrent writes.
 * @author toad
 */
public interface LockableRandomAccessThing extends RandomAccessThing {
	
    /** Keep the RAF open. Does not prevent others from writing to it. */
	public void lock();
	
	public void unlock();

}
