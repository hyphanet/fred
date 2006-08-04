package freenet.client.async;

import freenet.support.io.Bucket;

public interface HealingQueue {

	/** Queue a Bucket of data to insert as a CHK. */
	void queue(Bucket data);

}
