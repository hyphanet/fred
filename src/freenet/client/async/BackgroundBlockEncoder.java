package freenet.client.async;

import java.lang.ref.SoftReference;
import java.util.ArrayList;

import freenet.support.Logger;

/**
 * Keeps a queue of SingleBlockInserter's to encode.
 * Encodes them.
 */
public class BackgroundBlockEncoder implements Runnable {

	// Minimize memory usage at the cost of having to encode from the end
	private final ArrayList queue;
	
	public BackgroundBlockEncoder() {
		queue = new ArrayList();
	}
	
	public void queue(SingleBlockInserter sbi) {
		if(sbi.isCancelled()) return;
		if(sbi.resultingURI != null) return;
		SoftReference ref = new SoftReference(sbi);
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
				SoftReference ref = new SoftReference(inserter);
				queue.add(ref);
			}
			notifyAll();
		}
	}
	
	public void run() {
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
					SoftReference ref = (SoftReference) queue.remove(queue.size()-1);
					sbi = (SingleBlockInserter) ref.get();
					if(sbi != null) break;
				}
			}
			Logger.minor(this, "Encoding "+sbi);
			if(sbi.isCancelled()) continue;
			if(sbi.resultingURI != null) continue;
			sbi.tryEncode();
		}
	}

}
