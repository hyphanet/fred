package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.support.PooledExecutor;
import freenet.support.io.NativeThread;
import junit.framework.TestCase;

public class TrivialDBJobRunnerTest extends TestCase {
	
	public void testBlocking() throws DatabaseDisabledException {
		TrivialDBJobRunner jobRunner = new TrivialDBJobRunner(null);
		jobRunner.start(new PooledExecutor(), null);
		jobRunner.runBlocking(new DBJob() {

			public boolean run(ObjectContainer container, ClientContext context) {
				// Do nothing.
				return false;
			}
			
		}, NativeThread.NORM_PRIORITY);
	}

}
