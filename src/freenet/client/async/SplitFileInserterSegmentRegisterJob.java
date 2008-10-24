package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;

public class SplitFileInserterSegmentRegisterJob implements DBJob {

	final SplitFileInserterSegment seg;
	
	final int restartPriority;
	
	public SplitFileInserterSegmentRegisterJob(SplitFileInserterSegment segment, int restartPriority) {
		seg = segment;
		this.restartPriority = restartPriority;
	}

	public void run(ObjectContainer container, ClientContext context) {
		container.activate(seg, 1);
		try {
			seg.start(container, context);
		} catch (InsertException e) {
			container.activate(seg.parent, 1);
			seg.finish(e, container, context, seg.parent);
			container.deactivate(seg.parent, 1);
		}
		container.deactivate(seg, 1);
		context.jobRunner.removeRestartJob(this, restartPriority, container);
		container.delete(this);
	}

	public void schedule(ObjectContainer container, ClientContext context, int nowPriority, boolean persistent) {
		if(persistent)
			context.jobRunner.queueRestartJob(this, restartPriority, container);
		context.jobRunner.queue(this, nowPriority, false);
	}

}
