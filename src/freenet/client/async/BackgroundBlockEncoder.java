package freenet.client.async;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

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
		if(sbi.isFinished()) return;
		if(sbi.resultingURI != null) return;
		WeakReference ref = new WeakReference(sbi);
		synchronized(this) {
			queue.add(ref);
			notifyAll();
		}
	}
	
	public void queue(SingleBlockInserter[] sbis) {
		synchronized(this) {
			for(int i=0;i<sbis.length;i++) {
				if(sbis[i].isFinished()) continue;
				if(sbis[i].resultingURI != null) continue;
				WeakReference ref = new WeakReference(sbis[i]);
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
					WeakReference ref = (WeakReference) queue.remove(queue.size()-1);
					sbi = (SingleBlockInserter) ref.get();
					if(sbi != null) break;
				}
			}
			if(sbi.isFinished()) continue;
			if(sbi.resultingURI != null) continue;
			sbi.tryEncode();
		}
	}

}
