package freenet.support.io;

/** A RandomAccessThing which allows you to lock it open for a brief period to indicate that you are
 * using it and it would be a bad idea to close the pooled fd.
 * @author toad
 */
public interface LockableRandomAccessThing extends RandomAccessThing {
	
	public void lock();
	
	public void unlock();

}
