package freenet.client.async;

import java.lang.ref.SoftReference;
import java.util.ArrayList;

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
	
	public BackgroundBlockEncoder() {
		queue = new ArrayList<SoftReference<SingleBlockInserter>>();
	}
	
	public void queue(SingleBlockInserter sbi) {
		if(sbi.isCancelled()) return;
		if(sbi.resultingURI != null) return;
		SoftReference<SingleBlockInserter> ref = new SoftReference<SingleBlockInserter>(sbi);
		synchronized(this) {
			queue.add(ref);
			Logger.minor(this, "Queueing encode of "+sbi);
			notifyAll();
		}
	}
	
	public void queue(SingleBlockInserter[] sbis) {
		synchronized(this) {
			for(int i=0;i<sbis.length;i++) {
				SingleBlockInserter inserter = sbis[i];
				if(inserter == null) continue;
				if(inserter.isCancelled()) continue;
				if(inserter.resultingURI != null) continue;
				Logger.minor(this, "Queueing encode of "+inserter);
				SoftReference<SingleBlockInserter> ref = new SoftReference<SingleBlockInserter>(inserter);
				queue.add(ref);
			}
			notifyAll();
		}
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
			if(sbi.isCancelled()) continue;
			if(sbi.resultingURI != null) continue;
			sbi.tryEncode();
		}
	}

	public int getPriority() {
		return NativeThread.MIN_PRIORITY;
	}

}
