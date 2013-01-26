package freenet.client.async;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Random;

import freenet.support.math.MersenneTwister;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

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

	static final int MAX_PERSISTENT_KEYS = 1024;

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
	private static final class PersistentItem extends QueueItem {
		/** Arrays of keys to check. */
		Key[] keys;
		final ClientRequestScheduler scheduler;
		final DatastoreCheckerItem checkerItem;
		final BlockSet blockSet;
		PersistentItem(Key[] keys, SendableGet getter, ClientRequestScheduler scheduler, DatastoreCheckerItem checkerItem, BlockSet blockSet) {
			super(getter);
			this.keys = keys;
			this.scheduler = scheduler;
			this.checkerItem = checkerItem;
			this.blockSet = blockSet;
		}
	}
	/** List of persistent requests information. PARTIAL:
	 * When we run out we will look up some more DatastoreCheckerItem's. */
	private final ArrayDeque<PersistentItem>[] persistentQueue;

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
		persistentQueue = new ArrayDeque[priorities];
		for(int i=0;i<priorities;i++)
			persistentQueue[i] = new ArrayDeque<PersistentItem>();
		transientQueue = new ArrayDeque[priorities];
		for(int i=0;i<priorities;i++)
			transientQueue[i] = new ArrayDeque<TransientItem>();
	}

	private final DBJob loader =  new DBJob() {

		@Override
		public boolean run(ObjectContainer container, ClientContext context) {
			loadPersistentRequests(container, context);
			return false;
		}
		
		@Override
		public String toString() {
			return "DatastoreCheckerPersistentRequestLoader";
		}

	};

    public void loadPersistentRequests(ObjectContainer container, final ClientContext context) {
		int totalSize = 0;
		synchronized(this) {
			for(ArrayDeque<PersistentItem> queue: persistentQueue) {
				for(PersistentItem item: queue) {
					totalSize += item.keys.length;
				}
			}
			if(totalSize > MAX_PERSISTENT_KEYS) {
				if(logMINOR) Logger.minor(this, "Persistent datastore checker queue already full");
				return;
			}
		}
		for(short p = RequestStarter.MAXIMUM_PRIORITY_CLASS; p <= RequestStarter.MINIMUM_PRIORITY_CLASS; p++) {
			final short prio = p;
			Query query = container.query();
			query.constrain(DatastoreCheckerItem.class);
			query.descend("nodeDBHandle").constrain(context.nodeDBHandle).
				and(query.descend("prio").constrain(prio));
			@SuppressWarnings("unchecked")
			ObjectSet<DatastoreCheckerItem> results = query.execute();
			for(DatastoreCheckerItem item : results) {
				if(item.chosenBy == context.bootID) continue;
				SendableGet getter = item.getter;
				if(getter == null || !container.ext().isStored(getter)) {
					if(logMINOR) Logger.minor(this, "Ignoring DatastoreCheckerItem because the SendableGet has already been deleted from the database");
					container.delete(item);
					continue;
				}
				try {
					BlockSet blocks = item.blocks;
					container.activate(getter, 1);
					if(getter.isStorageBroken(container)) {
						Logger.error(this, "Getter is broken as stored: "+getter);
						container.delete(getter);
						container.delete(item);
						continue;
					}
					ClientRequestScheduler sched = getter.getScheduler(container, context);
					PersistentItem persist = new PersistentItem(
									null, getter, sched, item, blocks);
					Key[] keys = getter.listKeys(container);
					// FIXME check the store bloom filter using store.probablyInStore().
					item.chosenBy = context.bootID;
					container.store(item);
					ArrayList<Key> finalKeysToCheck = new ArrayList<Key>(keys.length);
					for(Key key : keys) {
						key = key.cloneKey();
						finalKeysToCheck.add(key);
					}
					Key[] finalKeys = finalKeysToCheck.toArray(new Key[finalKeysToCheck.size()]);
					persist.keys = finalKeys;
					synchronized(this) {
						if(persistentQueue[prio].contains(persist)) continue;
						persistentQueue[prio].add(persist);
						if(totalSize == 0)
							notifyAll();
						totalSize += finalKeys.length;
						if(totalSize > MAX_PERSISTENT_KEYS) {
							boolean full = trimPersistentQueue(prio, container);
							notifyAll();
							if(full) return;
						} else {
							notifyAll();
						}
					}
					container.deactivate(getter, 1);
				} catch (NullPointerException e) {
					Logger.error(this, "NPE for getter in DatastoreChecker: "+e+" - probably leftover data from an incomplete deletion", e);
					try {
						Logger.error(this, "Getter: "+getter);
					} catch (Throwable t) {
						// Ignore
					}
				}
			}
		}
	}

	/**
	 * Trim the queue of persistent requests until it is just over the limit.
	 * @param minPrio Only drop from priorities lower than this one.
	 * @return True unless the queue is under the limit.
	 */
	private boolean trimPersistentQueue(short prio, ObjectContainer container) {
		synchronized(this) {
			int preQueueSize = 0;
			for(int i=0;i<prio;i++) {
				for(PersistentItem item: persistentQueue[i]) {
					preQueueSize += item.keys.length;
				}
			}
			if(preQueueSize > MAX_PERSISTENT_KEYS) {
				// Dump everything
				for(int i=prio+1;i<persistentQueue.length;i++) {
					for(PersistentItem item : persistentQueue[i]) {
						item.checkerItem.chosenBy = 0;
						container.store(item.checkerItem);
					}
					persistentQueue[i].clear();
				}
				return true;
			} else {
				int postQueueSize = 0;
				for(int i=prio+1;i<persistentQueue.length;i++) {
					for(PersistentItem item: persistentQueue[i]) {
						postQueueSize += item.keys.length;
					}
				}
				if(postQueueSize + preQueueSize < MAX_PERSISTENT_KEYS)
					return false;
				// Need to dump some stuff.
				for(int i=persistentQueue.length-1;i>prio;i--) {
					PersistentItem item;
					while((item = persistentQueue[i].pollLast()) != null) {
						item.checkerItem.chosenBy = 0;
						container.store(item.checkerItem);
						postQueueSize -= item.keys.length;
						if(postQueueSize + preQueueSize < MAX_PERSISTENT_KEYS) {
							return false;
						}
					}
				}
				// Still over the limit.
				return true;
			}
		}
	}

	public void queueTransientRequest(SendableGet getter, BlockSet blocks) {
		Key[] checkKeys = getter.listKeys(null);
		short prio = getter.getPriorityClass(null);
		if(logMINOR) Logger.minor(this, "Queueing transient request "+getter+" priority "+prio+" keys "+checkKeys.length);
		// FIXME check using store.probablyInStore
		ArrayList<Key> finalKeysToCheck = new ArrayList<Key>(checkKeys.length);
		// Add it to the list of requests running here, so that priority changes while the data is on the store checker queue will work.
		ClientRequester requestor = getter.getClientRequest();
		requestor.addToRequests(getter, null);
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

	/**
	 * Queue a persistent request. We will store a DatastoreCheckerItem, then
	 * check the datastore (on the datastore checker thread), and then call
	 * finishRegister() (on the database thread). Caller must have already
	 * stored and registered the HasKeyListener if any.
	 * @param getter
	 */
	public void queuePersistentRequest(SendableGet getter, BlockSet blocks, ObjectContainer container, ClientContext context) {
		if(getter.isCancelled(container)) { // We do not care about cooldowns here; we will check all keys regardless, so only ask isCancelled().
			if(logMINOR) Logger.minor(this, "Request is empty, not checking store: "+getter);
			return;
		}
		Key[] checkKeys = getter.listKeys(container);
		short prio = getter.getPriorityClass(container);
		ClientRequestScheduler sched = getter.getScheduler(container, context);
		DatastoreCheckerItem item = new DatastoreCheckerItem(getter, context.nodeDBHandle, prio, blocks);
		container.store(item);
		container.activate(blocks, 5);
		// Add it to the list of requests running here, so that priority changes while the data is on the store checker queue will work.
		ClientRequester requestor = getter.getClientRequest();
		container.activate(requestor, 1);
		requestor.addToRequests(getter, container);
		synchronized(this) {
			// FIXME only add if queue not full.
			int queueSize = 0;
			// Only count queued keys at no higher priority than this request.
			for(short p = 0;p<=prio;p++) {
				for(PersistentItem persist: persistentQueue[p]) {
					queueSize += persist.keys.length;
				}
			}
			// Item is stored, we will get to it eventually.
			if(queueSize > MAX_PERSISTENT_KEYS) return;
			item.chosenBy = context.bootID;
			container.store(item);
			// FIXME check using store.probablyInStore
			ArrayList<Key> finalKeysToCheck = new ArrayList<Key>(checkKeys.length);
			for(Key key : checkKeys) {
				finalKeysToCheck.add(key);
			}
			PersistentItem queueItem = new PersistentItem(
					finalKeysToCheck.toArray(new Key[finalKeysToCheck.size()]),
					getter,	sched, item, blocks);
			// Paranoid check on heavy logging.
			if(logMINOR && persistentQueue[prio].contains(queueItem)) {
				Logger.error(this, "Persistent request "+getter+" is already queued!");
				return;
			}
			persistentQueue[prio].add(queueItem);
			trimPersistentQueue(prio, container);
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
		boolean persistent = false;
		ClientRequestScheduler sched = null;
		DatastoreCheckerItem item = null;
		BlockSet blocks = null;
		// If the queue is too large, don't check any more blocks. It is possible
		// that we can check the datastore faster than we can handle the resulting
		// blocks, this will cause OOM.
		int queueSize = context.jobRunner.getQueueSize(ClientRequestScheduler.TRIP_PENDING_PRIORITY);
		// If it's over 100, don't check blocks from persistent requests.
		boolean notPersistent = queueSize > 100;
		// FIXME: Ideally, for really big queues, we wouldn't datastore check transient keys that are also wanted by persistent requests.
		// Looking it up in the bloom filters is trivial. But I am not sure it is safe to take the CRSBase lock inside the DatastoreChecker lock,
		// especially given that sometimes SendableGet methods get called within it, and sometimes those call back here.
		// Maybe we can separate the lock for the Bloom filters from that for everything else?
		// Checking whether keys are wanted by persistent requests outside the lock would likely result in busy-looping.
		boolean waited = false;
		synchronized(this) {
			while(true) {
				for(short prio = 0;prio<transientQueue.length;prio++) {
					TransientItem trans;
					PersistentItem persist;
					if((trans = transientQueue[prio].pollFirst()) != null) {
						keys = trans.keys;
						getter = trans.getter;
						persistent = false;
						// sched assigned out of loop
						item = null;
						blocks = trans.blockSet;
						if(logMINOR)
							Logger.minor(this, "Checking transient request "+getter+" prio "+prio+" of "+transientQueue[prio].size());
						break;
					} else if((!notPersistent) && (persist = persistentQueue[prio].pollFirst()) != null) {
						keys = persist.keys;
						getter = persist.getter;
						persistent = true;
						sched = persist.scheduler;
						item = persist.checkerItem;
						blocks = persist.blockSet;
						if(logMINOR)
							Logger.minor(this, "Checking persistent request at prio "+prio);
						break;
					}
				}
				if(keys != null)
					break;
				if(!notPersistent) {
					try {
						context.jobRunner.queue(loader, NativeThread.HIGH_PRIORITY, true);
					} catch (DatabaseDisabledException e1) {
						// Ignore
					}
					if(logMINOR) Logger.minor(this, "Waiting for more persistent or transient requests");
				} else {
					if(logMINOR) Logger.minor(this, "Waiting for more transient requests");
				}
				if(waited && notPersistent) return; // Re-check queueSize after a failed wait.
				waited = true;
				try {
					// Wait for anything.
					wait(100*1000);
				} catch (InterruptedException e) {
					// Ok
				}
			}
		}
		if(!persistent) {
			sched = getter.getScheduler(null, context);
		}
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
		if(persistent)
			try {
				context.jobRunner.queue(loader, NativeThread.HIGH_PRIORITY, true);
			} catch (DatabaseDisabledException e) {
				// Ignore
			}
		if(persistent) {
			final SendableGet get = getter;
			final ClientRequestScheduler scheduler = sched;
			final boolean valid = anyValid;
			final DatastoreCheckerItem it = item;
			try {
				context.jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						if(container.ext().isActive(get)) {
							Logger.warning(this, "ALREADY ACTIVATED: "+get);
						}
						if(!container.ext().isStored(get)) {
							// Completed and deleted already.
							if(logMINOR)
								Logger.minor(this, "Already deleted from database");
							container.delete(it);
							return false;
						}
						container.activate(get, 1);
						try {
							scheduler.finishRegister(new SendableGet[] { get }, true, container, valid, it);
						} catch (Throwable t) {
							Logger.error(this, "Failed to register "+get+": "+t, t);
							try {
								get.onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR, "Internal error: "+t, t), null, container, context);
							} catch (Throwable t1) {
								Logger.error(this, "Failed to fail: "+t, t);
							}
						}
						container.deactivate(get, 1);
						loader.run(container, context);
						return false;
					}
					
					@Override
					public String toString() {
						return "DatastoreCheckerFinishRegister";
					}

				}, NativeThread.NORM_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
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
		try {
			context.jobRunner.queue(loader, NativeThread.HIGH_PRIORITY-1, true);
		} catch (DatabaseDisabledException e) {
			// Ignore
		}
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
		if(!persistent) {
			synchronized(this) {
				if(!transientQueue[prio].remove(requestMatcher)) return;
			}
			if(logMINOR) Logger.minor(this, "Removed transient request");
		} else {
			synchronized(this) {
				persistentQueue[prio].remove(requestMatcher);
			}
			// Find and delete the old item.
			Query query =
				container.query();
			query.constrain(DatastoreCheckerItem.class);
			query.descend("getter").constrain(request).identity();
			ObjectSet<DatastoreCheckerItem> results = query.execute();
			int deleted = 0;
			for(DatastoreCheckerItem item : results) {
				if(item.nodeDBHandle != context.nodeDBHandle) continue;
				if(deleted == 1) {
					try {
						Logger.error(this, "Multiple DatastoreCheckerItem's for "+request);
					} catch (Throwable e) {
						// Ignore, toString() error
						Logger.error(this, "Multiple DatastoreCheckerItem's for request");
					}
				}
				deleted++;
				container.delete(item);
			}
		}
	}

}
