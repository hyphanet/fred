package freenet.support;

import freenet.node.PrioRunnable;
import freenet.support.io.NativeThread;
import junit.framework.TestCase;

public class SerialExecutorTest extends TestCase {
	
	public void testBlocking() {
		SerialExecutor exec = new SerialExecutor(NativeThread.NORM_PRIORITY);
		exec.start(new PooledExecutor(), "test");
		final MutableBoolean flag = new MutableBoolean();
		exec.execute(new PrioRunnable() {

			public void run() {
				try {
					// Do nothing
				} finally {
					synchronized(flag) {
						flag.value = true;
						flag.notifyAll();
					}
				}
				
			}

			public int getPriority() {
				return NativeThread.NORM_PRIORITY;
			}
			
		});
		synchronized(flag) {
			while(!flag.value) {
				try {
					flag.wait();
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
	}

}
