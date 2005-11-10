package freenet.client;

import freenet.client.RetryTracker.Level;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;

/** Simple interface for a splitfile block */
public abstract class SplitfileBlock {

	/** Get block number. [0,k[ = data blocks, [k, n[ = check blocks */
	abstract int getNumber();
	
	/** Has data? */
	abstract boolean hasData();
	
	/** Get data */
	abstract Bucket getData();
	
	/** Set data */
	abstract void setData(Bucket data);

	/** Start the fetch (or insert). Implementation is required to call relevant
	 * methods on RetryTracker when done. */
	abstract void start();

	/**
	 * Shut down the fetch as soon as reasonably possible.
	 */
	abstract public void kill();

	/**
	 * Get the URI of the file. For an insert, this is derived during insert.
	 * For a request, it is fixed in the constructor.
	 */
	abstract public FreenetURI getURI();

	abstract public int getRetryCount();
}
