package freenet.client.async;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Random;

import freenet.support.math.MersenneTwister;

import com.db4o.ObjectContainer;

import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeSSK;
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.node.PrioRunnable;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.support.Executor;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class DatastoreChecker implements PrioRunnable {

	// Setting these to 1, 3 kills 1/3rd of datastore checks.
	// 2, 5 gives 40% etc.
	// In normal operation KILL_BLOCKS should be 0 !!!!
	static final int KILL_BLOCKS = 0;
	static final int RESET_COUNTER = 100;
	
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	private static class QueueItem {
		/** Request which we will call finishRegister() for when we have
		 *  checked the keys lists. Deactivated (if persistent). */
		final SendableGet getter;
		QueueItem(SendableGet getter) {
			this.getter = getter;
		}
		public boolean equals(Object o) {
			if(!(o instanceof QueueItem)) return false; // equals() should not throw ClassCastException
			return this.getter == ((QueueItem)o).getter;
		}
	}

	private static final class TransientItem extends QueueItem {
		/** Arrays of keys to check. */
		Key[] keys;
		final BlockSet blockSet;
		TransientItem(Key[] keys, SendableGet getter, BlockSet blockSet) {
			super(getter);
			this.keys = keys;
			this.blockSet = blockSet;
		}
	}
	/** List of transient requests information. */
	private final ArrayDeque<TransientItem>[] transientQueue;

	private ClientContext context;
	private final Node node;

	public synchronized void setContext(ClientContext context) {
		this.context = context;
	}

	@SuppressWarnings("unchecked")
    public DatastoreChecker(Node node) {
		this.node = node;
		int priorities = RequestStarter.NUMBER_OF_PRIORITY_CLASSES;
		transientQueue = new ArrayDeque[priorities];
		for(int i=0;i<priorities;i++)
			transientQueue[i] = new ArrayDeque<TransientItem>();
	}

	public void queueTransientRequest(SendableGet getter, BlockSet blocks) {
		Key[] checkKeys = getter.listKeys(null);
		short prio = getter.getPriorityClass();
		if(logMINOR) Logger.minor(this, "Queueing transient request "+getter+" priority "+prio+" keys "+checkKeys.length);
		// FIXME check using store.probablyInStore
		ArrayList<Key> finalKeysToCheck = new ArrayList<Key>(checkKeys.length);
		// Add it to the list of requests running here, so that priority changes while the data is on the store checker queue will work.
		ClientRequester requestor = getter.getClientRequest();
		requestor.addToRequests(getter);
		synchronized(this) {
			for(Key key : checkKeys) {
				finalKeysToCheck.add(key);
			}
			TransientItem queueItem = new TransientItem(
					finalKeysToCheck.toArray(new Key[finalKeysToCheck.size()]),
					getter, blocks);
			if(logMINOR && transientQueue[prio].contains(queueItem)) {
				Logger.error(this, "Transient request "+getter+" is already queued!");
				return;
			}
			transientQueue[prio].add(queueItem);
			notifyAll();
		}
	}

	@Override
	public void run() {
		while(true) {
			try {
				realRun();
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t+" in datastore checker thread", t);
			}
		}
	}

	private void realRun() {
		Random random;
		if(KILL_BLOCKS != 0)
			random = new MersenneTwister();
		else
			random = null;
		Key[] keys = null;
		SendableGet getter = null;
		ClientRequestScheduler sched = null;
		DatastoreCheckerItem item = null;
		BlockSet blocks = null;
		boolean waited = false;
		synchronized(this) {
			while(true) {
				for(short prio = 0;prio<transientQueue.length;prio++) {
					TransientItem trans;
					if((trans = transientQueue[prio].pollFirst()) != null) {
						keys = trans.keys;
						getter = trans.getter;
						// sched assigned out of loop
						item = null;
						blocks = trans.blockSet;
						if(logMINOR)
							Logger.minor(this, "Checking transient request "+getter+" prio "+prio+" of "+transientQueue[prio].size());
						break;
					}
				}
				if(keys != null)
					break;
				if(logMINOR) Logger.minor(this, "Waiting for more transient requests");
				waited = true;
				try {
					// Wait for anything.
					wait(SECONDS.toMillis(100));
				} catch (InterruptedException e) {
					// Ok
				}
			}
		}
		sched = getter.getScheduler(context);
		boolean anyValid = false;
		for(Key key : keys) {
			if(random != null) {
				if(random.nextInt(RESET_COUNTER) < KILL_BLOCKS) {
					anyValid = true;
					continue;
				}
			}
			KeyBlock block;
			if(blocks != null)
				block = blocks.get(key);
			else
				block = node.fetch(key, true, true, false, false, null);
			if(block != null) {
				if(logMINOR) Logger.minor(this, "Found key");
				if(key instanceof NodeSSK)
					sched.tripPendingKey(block);
				else // CHK
					sched.tripPendingKey(block);
			} else {
				anyValid = true;
			}
//			synchronized(this) {
//				keysToCheck[priority].remove(key);
//			}
		}
		if(logMINOR) Logger.minor(this, "Checked "+keys.length+" keys");
		if(getter.persistent()) {
			final SendableGet get = getter;
			final ClientRequestScheduler scheduler = sched;
			final boolean valid = anyValid;
			final DatastoreCheckerItem it = item;
			try {
				context.jobRunner.queue(new PersistentJob() {

					@Override
					public boolean run(ClientContext context) {
						try {
							scheduler.finishRegister(new SendableGet[] { get }, true, null, valid, it);
						} catch (Throwable t) {
							Logger.error(this, "Failed to register "+get+": "+t, t);
							try {
								get.onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR, "Internal error: "+t, t), null, null, context);
							} catch (Throwable t1) {
								Logger.error(this, "Failed to fail: "+t, t);
							}
						}
						return false;
					}
					
					@Override
					public String toString() {
						return "DatastoreCheckerFinishRegister";
					}

				}, NativeThread.NORM_PRIORITY);
			} catch (PersistenceDisabledException e) {
				// Impossible
			}
		} else {
			sched.finishRegister(new SendableGet[] { getter }, false, null, anyValid, item);
		}
	}

	synchronized void wakeUp() {
		notifyAll();
	}

	public void start(Executor executor, String name) {
		executor.execute(this, name);
	}

	@Override
	public int getPriority() {
		return NativeThread.NORM_PRIORITY;
	}

	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing DatastoreChecker in database", new Exception("error"));
		return false;
	}

	@SuppressWarnings("unchecked")
	public void removeRequest(SendableGet request, boolean persistent, ObjectContainer container, ClientContext context, short prio) {
		if(logMINOR) Logger.minor(this, "Removing request prio="+prio+" persistent="+persistent);
		QueueItem requestMatcher = new QueueItem(request);
		synchronized(this) {
		    if(!transientQueue[prio].remove(requestMatcher)) return;
		}
		if(logMINOR) Logger.minor(this, "Removed transient request");
	}

}
