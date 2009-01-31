package freenet.support.io;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.support.Logger;

public class SegmentedBucketChainBucketKillJob implements DBJob {
	
	final SegmentedBucketChainBucket bcb;

	private final short RESTART_PRIO = NativeThread.HIGH_PRIORITY;
	
	public SegmentedBucketChainBucketKillJob(SegmentedBucketChainBucket bucket) {
		bcb = bucket;
	}

	public void run(ObjectContainer container, ClientContext context) {
		container.activate(bcb, 2);
		Logger.normal(this, "Freeing unfinished unstored bucket "+this);
		// Restart jobs runner will remove us from the queue.
		// This may take more than one transaction ...
		if(bcb.removeContents(container)) {
			// More work needs to be done.
			// We will have already been removed, so re-add, in case we crash soon.
			scheduleRestart(container, context);
			// But try to sort it out now ...
			context.jobRunner.queue(this, NativeThread.NORM_PRIORITY, true);
		} else {
			context.jobRunner.removeRestartJob(this, RESTART_PRIO, container);
		}
	}
	
	public void scheduleRestart(ObjectContainer container, ClientContext context) {
		context.jobRunner.queueRestartJob(this, RESTART_PRIO, container, true);
	}
	
}
