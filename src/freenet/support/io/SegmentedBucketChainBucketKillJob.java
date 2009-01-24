package freenet.support.io;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.support.Logger;

public class SegmentedBucketChainBucketKillJob implements DBJob {
	
	final SegmentedBucketChainBucket bcb;

	public SegmentedBucketChainBucketKillJob(SegmentedBucketChainBucket bucket) {
		bcb = bucket;
	}

	public void run(ObjectContainer container, ClientContext context) {
		container.activate(bcb, 2);
		System.err.println("Freeing unfinished unstored bucket "+this);
		Logger.error(this, "Freeing unfinished unstored bucket "+this);
		bcb.removeContents(container);
	}

}
