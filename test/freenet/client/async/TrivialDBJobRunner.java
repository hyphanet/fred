package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.client.async.DatabaseDisabledException;
import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.MutableBoolean;
import freenet.support.SerialExecutor;
import freenet.support.io.NativeThread;

public class TrivialDBJobRunner implements DBJobRunner {

	private final SerialExecutor executor;
	private final ObjectContainer container;
	private ClientContext context;
	
	public TrivialDBJobRunner(ObjectContainer container) {
		executor = new SerialExecutor(NativeThread.NORM_PRIORITY);
		this.container = container;
	}
	
	public void start(Executor baseExec, ClientContext context) {
		this.context = context;
		executor.start(baseExec, "Test executor");
	}

	@Override
	public void queue(final DBJob job, final int priority, boolean checkDupes)
			throws DatabaseDisabledException {
		if(checkDupes) throw new UnsupportedOperationException();
		executor.execute(new PrioRunnable() {

			@Override
			public void run() {
				job.run(container, context);
				container.commit();
			}

			@Override
			public int getPriority() {
				return priority;
			}
			
		});
	}

	@Override
	public void runBlocking(final DBJob job, final int priority)
			throws DatabaseDisabledException {
		if(onDatabaseThread()) {
			job.run(container, context);
		} else {
			final MutableBoolean flag = new MutableBoolean();
			executor.execute(new PrioRunnable() {

				@Override
				public void run() {
					try {
						job.run(container, context);
						container.commit();
					} finally {
						synchronized(flag) {
							flag.value = true;
							flag.notifyAll();
						}
					}
					
				}

				@Override
				public int getPriority() {
					return priority;
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

	@Override
	public boolean onDatabaseThread() {
		return executor.onThread();
	}

	@Override
	public int getQueueSize(int priority) {
		return executor.waitingThreads()[priority];
	}

	@Override
	public void queueRestartJob(DBJob job, int priority,
			ObjectContainer container, boolean early)
			throws DatabaseDisabledException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void removeRestartJob(DBJob job, int priority,
			ObjectContainer container) throws DatabaseDisabledException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean killedDatabase() {
		return false;
	}

	@Override
	public void setCommitThisTransaction() {
		// Ignore
	}

	@Override
	public void setCommitSoon() {
		// Ignore
	}

}