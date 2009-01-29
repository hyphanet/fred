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
		// Restart jobs runner will remove us from the queue.
		// This may take more than one transaction ...
		if(bcb.removeContents(container)) {
			// More work needs to be done.
			// We will have already been removed, so re-add, in case we crash soon.
			context.jobRunner.queueRestartJob(this, NativeThread.HIGH_PRIORITY, container);
			// But try to sort it out now ...
			context.jobRunner.queue(this, NativeThread.NORM_PRIORITY, true);
		}
	}

}
