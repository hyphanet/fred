package freenet.client.async;

import static java.util.concurrent.TimeUnit.SECONDS;

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
	private final ArrayList<SoftReference<Encodeable>> queue;
	private ClientContext context;
	
	public BackgroundBlockEncoder() {
		queue = new ArrayList<SoftReference<Encodeable>>();
	}
	
	public void setContext(ClientContext context) {
		this.context = context;
	}
	
	public void queue(Encodeable sbi, ClientContext context) {
	    SoftReference<Encodeable> ref = new SoftReference<Encodeable>(sbi);
	    synchronized(this) {
	        queue.add(ref);
	        Logger.minor(this, "Queueing encode of "+sbi);
	        notifyAll();
		}
	}
	
	@Override
	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
		while(true) {
			Encodeable sbi = null;
			synchronized(this) {
				while(queue.isEmpty()) {
					try {
						wait(SECONDS.toMillis(100));
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
			sbi.tryEncode(context);
		}
	}

	@Override
	public int getPriority() {
		return NativeThread.MIN_PRIORITY;
	}

}
