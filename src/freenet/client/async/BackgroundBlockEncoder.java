package freenet.client.async;

import java.lang.ref.SoftReference;
import java.util.ArrayList;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.node.PrioRunnable;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * Keeps a queue of SingleBlockInserter's to encode.
 * Encodes them.
 */
public class BackgroundBlockEncoder implements PrioRunnable {

	// Minimize memory usage at the cost of having to encode from the end
	private final ArrayList<SoftReference<SingleBlockInserter>> queue;
	private ClientContext context;
	
	public BackgroundBlockEncoder() {
		queue = new ArrayList<SoftReference<SingleBlockInserter>>();
	}
	
	public void setContext(ClientContext context) {
		this.context = context;
	}
	
	public void queue(SingleBlockInserter sbi, ObjectContainer container, ClientContext context) {
		if(sbi.isCancelled(container)) return;
		if(sbi.resultingURI != null) return;
		if(sbi.persistent()) {
			queuePersistent(sbi, container, context);
			runPersistentQueue(context);
		} else {
			SoftReference<SingleBlockInserter> ref = new SoftReference<SingleBlockInserter>(sbi);
			synchronized(this) {
				queue.add(ref);
				Logger.minor(this, "Queueing encode of "+sbi);
				notifyAll();
			}
		}
	}
	
	public void queue(SingleBlockInserter[] sbis, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			for(int i=0;i<sbis.length;i++) {
				SingleBlockInserter inserter = sbis[i];
				if(inserter == null) continue;
				if(inserter.isCancelled(container)) continue;
				if(inserter.resultingURI != null) continue;
				if(inserter.persistent()) continue;
				Logger.minor(this, "Queueing encode of "+inserter);
				SoftReference<SingleBlockInserter> ref = new SoftReference<SingleBlockInserter>(inserter);
				queue.add(ref);
			}
			notifyAll();
		}
		boolean anyPersistent = false;
		for(int i=0;i<sbis.length;i++) {
			anyPersistent = true;
			SingleBlockInserter inserter = sbis[i];
			if(inserter == null) continue;
			if(inserter.isCancelled(container)) continue;
			if(inserter.resultingURI != null) continue;
			if(!inserter.persistent()) continue;
			queuePersistent(inserter, container, context);
		}
		if(anyPersistent)
			runPersistentQueue(context);
	}
	
	private void runPersistentQueue(ClientContext context) {
		context.jobRunner.queue(runner, NativeThread.LOW_PRIORITY, true);
	}

	private void queuePersistent(SingleBlockInserter sbi, ObjectContainer container, ClientContext context) {
		BackgroundBlockEncoderTag tag = new BackgroundBlockEncoderTag(sbi, sbi.getPriorityClass(container), context);
		container.set(tag);
	}

	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
		while(true) {
			SingleBlockInserter sbi = null;
			synchronized(this) {
				while(queue.isEmpty()) {
					try {
						wait(100*1000);
					} catch (InterruptedException e) {
						// Ignore
					}
				}
				while(!queue.isEmpty()) {
					SoftReference<SingleBlockInserter> ref = queue.remove(queue.size()-1);
					sbi = ref.get();
					if(sbi != null) break;
				}
			}
			Logger.minor(this, "Encoding "+sbi);
			if(sbi.isCancelled(null)) continue;
			if(sbi.resultingURI != null) continue;
			sbi.tryEncode(null, context);
		}
	}

	public int getPriority() {
		return NativeThread.MIN_PRIORITY;
	}

	static final int JOBS_PER_SLOT = 1;
	
	private DBJob runner = new DBJob() {

		public void run(ObjectContainer container, ClientContext context) {
			Query query = container.query();
			query.constrain(BackgroundBlockEncoderTag.class);
			query.descend("nodeDBHandle").constrain(new Long(context.nodeDBHandle));
			query.descend("priority").orderAscending();
			query.descend("addedTime").orderAscending();
			ObjectSet results = query.execute();
			for(int x = 0; x < JOBS_PER_SLOT && results.hasNext(); x++) {
				BackgroundBlockEncoderTag tag = (BackgroundBlockEncoderTag) results.next();
				try {
					SingleBlockInserter sbi = tag.inserter;
					container.activate(sbi, 1);
					if(sbi == null) continue; // deleted
					if(sbi.isCancelled(container)) continue;
					if(sbi.resultingURI != null) continue;
					sbi.tryEncode(container, context);
					container.deactivate(sbi, 1);
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				} finally {
					container.delete(tag);
				}
			}
		}
		
	};
	
}

class BackgroundBlockEncoderTag {
	final SingleBlockInserter inserter;
	final long nodeDBHandle;
	/** For implementing FIFO ordering */
	final long addedTime;
	/** For implementing priority ordering */
	final short priority;
	
	BackgroundBlockEncoderTag(SingleBlockInserter inserter, short prio, ClientContext context) {
		this.inserter = inserter;
		this.nodeDBHandle = context.nodeDBHandle;
		this.addedTime = System.currentTimeMillis();
		this.priority = prio;
	}
}
