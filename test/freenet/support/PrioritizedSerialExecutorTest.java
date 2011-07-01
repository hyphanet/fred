package freenet.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;
import freenet.node.PrioRunnable;
import freenet.support.io.NativeThread;

public class PrioritizedSerialExecutorTest extends TestCase {
	private Executor realExec;
	private PrioritizedSerialExecutor exec;

	private SynchronousQueue<String> completingJob;
	private List<String> completedJobs;

	private class J implements PrioRunnable {
		private int prio;
		private String name;

		J(String name, int prio) {
			this.name = name;
			this.prio = prio;
		}

		@Override
		public int getPriority() {
			return prio;
		}

		@Override
		public void run() {
			synchronized (this) {
				notifyAll();
			}
			try {
				assertTrue(exec.onThread());
				completingJob.put(name);
			} catch (InterruptedException e) {
				fail(e.toString());
			}
		}
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		realExec = new PooledExecutor();
		completedJobs = new ArrayList<String>();
		completingJob = new SynchronousQueue<String>();
		exec = new PrioritizedSerialExecutor(NativeThread.MAX_PRIORITY, 10, 5, true);
	}

	private void Q(String j, int i, boolean waitForStart) throws InterruptedException {
		J job = new J(j, i);

		synchronized (job) {
			exec.execute(job, j);
			if (waitForStart)
				job.wait(5000);
		}
	}

	private void waitFor(int count) throws InterruptedException {
		int completed = 0;
		while (completed < count) {
			String s = completingJob.poll(5, TimeUnit.SECONDS);
			if (s == null)
				fail("Hang?");

			completed++;
			completedJobs.add(s);
		}
		System.out.println(completedJobs);
	}

	public void testRun() throws InterruptedException {
		assertTrue(completedJobs.isEmpty());

		Q("J1", 0, false);
		Q("J2", 0, false);
		Q("J3", 0, false);
		Q("J4", 0, false);
		Thread.yield();
		Thread.sleep(10);

		assertTrue(completedJobs.isEmpty()); // not started yet!

		exec.start(realExec, "testRun"); // start !
		waitFor(4);

		assertTrue(completedJobs.contains("J1"));
		assertTrue(completedJobs.contains("J2"));
		assertTrue(completedJobs.contains("J3"));
		assertTrue(completedJobs.contains("J4"));

		assertFalse(exec.onThread());
	}

	public void testRunPrio() throws InterruptedException {
		assertTrue(completedJobs.isEmpty());

		Q("JM", 9, false);
		Q("J8", 8, false);

		assertTrue(completedJobs.isEmpty()); // not started yet!

		assertEquals(0, exec.getWaitingThreadsCount());
		exec.start(realExec, "testRunPrio"); // start !

		waitFor(1); // JM

		Q("J2", 2, false);
		Q("JN", 4, false);
		Q("JO", 2, false);
		Q("JP", 3, false);

		assertEquals(0, exec.getQueueSize(9));
		assertEquals(1, exec.getQueueSize(3));
		assertEquals(2, exec.getQueueSize(2));

		waitFor(2); // J8,JN

		assertEquals(0, exec.getQueueSize(9));
		assertEquals(0, exec.getQueueSize(4));
		assertEquals(2, exec.getQueueSize(2));

		Thread.yield();
		Thread.sleep(10);

		Q("JQ", 4, false);
		Q("JR", 0, false);
		assertEquals(1, exec.getQueueSize(4));
		assertEquals(2, exec.getQueueSize(2));
		assertEquals(0, exec.getQueueSize(1));
		assertEquals(1, exec.getQueueSize(0));

		int[] r = exec.getQueuedJobsCountByPriority();
		assertTrue(
			Arrays.equals(new int[] { 1, 0, 2, 0, 1, 0, 0, 0, 0, 0 }, r) ||
			Arrays.equals(new int[] { 1, 0, 2, 1, 1, 0, 0, 0, 0, 0 }, r)
		);

		waitFor(5); // JP, JQ, J2, JO, JR

		int i = 0;
		for (String s : new String[] { "JM", "J8", "JN", "JP", "JQ", "J2", "JO", "JR" })
			assertEquals(s, s, completedJobs.get(i++));
	}
}
