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
	private final ArrayList<SoftReference<Encodeable>> queue;
	private ClientContext context;
	
	public BackgroundBlockEncoder() {
		queue = new ArrayList<SoftReference<Encodeable>>();
	}
	
	public void setContext(ClientContext context) {
		this.context = context;
	}
	
	public void queue(Encodeable sbi, ObjectContainer container, ClientContext context) {
		if(sbi.persistent()) {
			queuePersistent(sbi, container, context);
			runPersistentQueue(context);
		} else {
			SoftReference<Encodeable> ref = new SoftReference<Encodeable>(sbi);
			synchronized(this) {
				queue.add(ref);
				Logger.minor(this, "Queueing encode of "+sbi);
				notifyAll();
			}
		}
	}
	
	public void queue(SingleBlockInserter[] sbis, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			for(SingleBlockInserter inserter: sbis) {
				if(inserter == null) continue;
				if(inserter.isCancelled(container)) continue;
				if(inserter.resultingURI != null) continue;
				if(inserter.persistent()) continue;
				Logger.minor(this, "Queueing encode of "+inserter);
				SoftReference<Encodeable> ref = new SoftReference<Encodeable>(inserter);
				queue.add(ref);
			}
			notifyAll();
		}
		boolean anyPersistent = false;
		for(SingleBlockInserter inserter: sbis) {
			if(inserter == null) continue;
			if(inserter.isCancelled(container)) continue;
			if(inserter.resultingURI != null) continue;
			if(!inserter.persistent()) continue;
			anyPersistent = true;
			queuePersistent(inserter, container, context);
		}
		if(anyPersistent)
			runPersistentQueue(context);
	}
	
	public void runPersistentQueue(ClientContext context) {
		try {
			context.jobRunner.queue(runner, NativeThread.LOW_PRIORITY, true);
		} catch (DatabaseDisabledException e) {
			// Ignore
		}
	}

	private void queuePersistent(Encodeable sbi, ObjectContainer container, ClientContext context) {
		BackgroundBlockEncoderTag tag = new BackgroundBlockEncoderTag(sbi, sbi.getPriorityClass(container), context);
		container.store(tag);
	}

	@Override
	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
		while(true) {
			Encodeable sbi = null;
			synchronized(this) {
				while(queue.isEmpty()) {
					try {
						wait(100*1000);
					} catch (InterruptedException e) {
						// Ignore
					}
				}
				while(!queue.isEmpty()) {
					SoftReference<Encodeable> ref = queue.remove(queue.size()-1);
					sbi = ref.get();
					if(sbi != null) break;
				}
			}
			Logger.minor(this, "Encoding "+sbi);
			sbi.tryEncode(null, context);
		}
	}

	@Override
	public int getPriority() {
		return NativeThread.MIN_PRIORITY;
	}

	static final int JOBS_PER_SLOT = 1;
	
	private DBJob runner = new DBJob() {

		@Override
		public boolean run(ObjectContainer container, ClientContext context) {
			Query query = container.query();
			query.constrain(BackgroundBlockEncoderTag.class);
			query.descend("nodeDBHandle").constrain(Long.valueOf(context.nodeDBHandle));
			query.descend("priority").orderAscending();
			query.descend("addedTime").orderAscending();
			ObjectSet<BackgroundBlockEncoderTag> results = query.execute();
			for(int x = 0; x < JOBS_PER_SLOT && results.hasNext(); x++) {
				BackgroundBlockEncoderTag tag = results.next();
				try {
					Encodeable sbi = tag.inserter;
					if(sbi == null) continue;
					container.activate(sbi, 1);
					sbi.tryEncode(container, context);
					container.deactivate(sbi, 1);
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				} finally {
					container.delete(tag);
				}
			}
			if(results.hasNext())
				runPersistentQueue(context);
			return true;
		}
		
	};
	
}
