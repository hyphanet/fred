package freenet.support;

import static org.junit.Assert.*;

import org.junit.Test;

import freenet.node.PrioRunnable;
import freenet.support.io.NativeThread;

public class SerialExecutorTest {
	
	@Test
	public void testBlocking() {
		SerialExecutor exec = new SerialExecutor(NativeThread.NORM_PRIORITY);
		exec.start(new PooledExecutor(), "test");
		final MutableBoolean flag = new MutableBoolean();
		exec.execute(new PrioRunnable() {

			@Override
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

			@Override
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
