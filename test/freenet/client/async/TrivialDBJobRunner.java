package freenet.client.async;

import java.io.File;
import java.io.IOException;

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

	public void queue(final DBJob job, final int priority, boolean checkDupes)
			throws DatabaseDisabledException {
		if(checkDupes) throw new UnsupportedOperationException();
		executor.execute(new PrioRunnable() {

			public void run() {
				job.run(container, context);
				container.commit();
			}

			public int getPriority() {
				return priority;
			}
			
		});
	}

	public void runBlocking(final DBJob job, final int priority)
			throws DatabaseDisabledException {
		if(onDatabaseThread()) {
			job.run(container, context);
		} else {
			final MutableBoolean flag = new MutableBoolean();
			executor.execute(new PrioRunnable() {

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

	public boolean onDatabaseThread() {
		return executor.onThread();
	}

	public int getQueueSize(int priority) {
		return executor.waitingThreads()[priority];
	}

	public void queueRestartJob(DBJob job, int priority,
			ObjectContainer container, boolean early)
			throws DatabaseDisabledException {
		throw new UnsupportedOperationException();
	}

	public void removeRestartJob(DBJob job, int priority,
			ObjectContainer container) throws DatabaseDisabledException {
		throw new UnsupportedOperationException();
	}

	public boolean killedDatabase() {
		return false;
	}

	public void setCommitThisTransaction() {
		// Ignore
	}

	public void setCommitSoon() {
		// Ignore
	}

}