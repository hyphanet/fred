package freenet.client.async;

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

	/** List of arrays of keys to check for persistent requests. PARTIAL:
	 * When we run out we will look up some more DatastoreCheckerItem's. */
	private final ArrayList<Key[]>[] persistentKeys;
	/** List of persistent requests which we will call finishRegister() for
	 * when we have checked the keys lists. PARTIAL: When we run out we
	 * will look up some more DatastoreCheckerItem's. Deactivated. */
	private final ArrayList<SendableGet>[] persistentGetters;
	private final ArrayList<ClientRequestScheduler>[] persistentSchedulers;
	private final ArrayList<DatastoreCheckerItem>[] persistentCheckerItems;
	private final ArrayList<BlockSet>[] persistentBlockSets;

	/** List of arrays of keys to check for transient requests. */
	private final ArrayList<Key[]>[] transientKeys;
	/** List of transient requests which we will call finishRegister() for
	 * when we have checked the keys lists. */
	private final ArrayList<SendableGet>[] transientGetters;
	private final ArrayList<BlockSet>[] transientBlockSets;

	private ClientContext context;
	private final Node node;

	public synchronized void setContext(ClientContext context) {
		this.context = context;
	}

	@SuppressWarnings("unchecked")
    public DatastoreChecker(Node node) {
		this.node = node;
		int priorities = RequestStarter.NUMBER_OF_PRIORITY_CLASSES;
		persistentKeys = new ArrayList[priorities];
		for(int i=0;i<priorities;i++)
			persistentKeys[i] = new ArrayList<Key[]>();
		persistentGetters = new ArrayList[priorities];
		for(int i=0;i<priorities;i++)
			persistentGetters[i] = new ArrayList<SendableGet>();
		persistentSchedulers = new ArrayList[priorities];
		for(int i=0;i<priorities;i++)
			persistentSchedulers[i] = new ArrayList<ClientRequestScheduler>();
		persistentCheckerItems = new ArrayList[priorities];
		for(int i=0;i<priorities;i++)
			persistentCheckerItems[i] = new ArrayList<DatastoreCheckerItem>();
		persistentBlockSets = new ArrayList[priorities];
		for(int i=0;i<priorities;i++)
			persistentBlockSets[i] = new ArrayList<BlockSet>();
		transientKeys = new ArrayList[priorities];
		for(int i=0;i<priorities;i++)
			transientKeys[i] = new ArrayList<Key[]>();
		transientGetters = new ArrayList[priorities];
		for(int i=0;i<priorities;i++)
			transientGetters[i] = new ArrayList<SendableGet>();
		transientBlockSets = new ArrayList[priorities];
		for(int i=0;i<priorities;i++)
			transientBlockSets[i] = new ArrayList<BlockSet>();
	}

	private final DBJob loader =  new DBJob() {

		public boolean run(ObjectContainer container, ClientContext context) {
			loadPersistentRequests(container, context);
			return false;
		}
		
		public String toString() {
			return "DatastoreCheckerPersistentRequestLoader";
		}

	};

    public void loadPersistentRequests(ObjectContainer container, final ClientContext context) {
		int totalSize = 0;
		synchronized(this) {
			for(int i=0;i<persistentKeys.length;i++) {
				for(int j=0;j<persistentKeys[i].size();j++)
					totalSize += persistentKeys[i].get(j).length;
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
					synchronized(this) {
						if(persistentGetters[prio].contains(getter)) continue;
					}
					Key[] keys = getter.listKeys(container);
					// FIXME check the store bloom filter using store.probablyInStore().
					item.chosenBy = context.bootID;
					container.store(item);
					synchronized(this) {
						if(persistentGetters[prio].contains(getter)) continue;
						ArrayList<Key> finalKeysToCheck = new ArrayList<Key>();
						for(Key key : keys) {
							key = key.cloneKey();
							finalKeysToCheck.add(key);
						}
						Key[] finalKeys =
							finalKeysToCheck.toArray(new Key[finalKeysToCheck.size()]);
						persistentKeys[prio].add(finalKeys);
						persistentGetters[prio].add(getter);
						persistentSchedulers[prio].add(sched);
						persistentCheckerItems[prio].add(item);
						persistentBlockSets[prio].add(blocks);
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
				for(int x=0;x<persistentKeys[i].size();x++)
					preQueueSize += persistentKeys[i].get(x).length;
			}
			if(preQueueSize > MAX_PERSISTENT_KEYS) {
				// Dump everything
				for(int i=prio+1;i<persistentKeys.length;i++) {
					for(DatastoreCheckerItem item : persistentCheckerItems[i]) {
						item.chosenBy = 0;
						container.store(item);
					}
					persistentSchedulers[i].clear();
					persistentGetters[i].clear();
					persistentKeys[i].clear();
					persistentBlockSets[i].clear();
				}
				return true;
			} else {
				int postQueueSize = 0;
				for(int i=prio+1;i<persistentKeys.length;i++) {
					for(int x=0;x<persistentKeys[i].size();x++)
						postQueueSize += persistentKeys[i].get(x).length;
				}
				if(postQueueSize + preQueueSize < MAX_PERSISTENT_KEYS)
					return false;
				// Need to dump some stuff.
				for(int i=persistentKeys.length-1;i>prio;i--) {
					while(!persistentKeys[i].isEmpty()) {
						int idx = persistentKeys[i].size() - 1;
						DatastoreCheckerItem item = persistentCheckerItems[i].remove(idx);
						persistentSchedulers[i].remove(idx);
						persistentGetters[i].remove(idx);
						Key[] keys = persistentKeys[i].remove(idx);
						persistentBlockSets[i].remove(idx);
						item.chosenBy = 0;
						container.store(item);
						if(postQueueSize + preQueueSize - keys.length < MAX_PERSISTENT_KEYS) {
							return false;
						} else postQueueSize -= keys.length;
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
		ArrayList<Key> finalKeysToCheck = new ArrayList<Key>();
		// Add it to the list of requests running here, so that priority changes while the data is on the store checker queue will work.
		ClientRequester requestor = getter.getClientRequest();
		requestor.addToRequests(getter, null);
		synchronized(this) {
			for(Key key : checkKeys) {
				finalKeysToCheck.add(key);
			}
			if(logMINOR && transientGetters[prio].indexOf(getter) != -1) {
				Logger.error(this, "Transient request "+getter+" is already queued!");
				return;
			}
			transientGetters[prio].add(getter);
			transientKeys[prio].add(finalKeysToCheck.toArray(new Key[finalKeysToCheck.size()]));
			transientBlockSets[prio].add(blocks);
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
				for(int x = 0;x<persistentKeys[p].size();x++) {
					queueSize += persistentKeys[p].get(x).length;
				}
			}
			if(queueSize > MAX_PERSISTENT_KEYS) return;
			item.chosenBy = context.bootID;
			container.store(item);
			// FIXME check using store.probablyInStore
			ArrayList<Key> finalKeysToCheck = new ArrayList<Key>();
			for(Key key : checkKeys) {
				finalKeysToCheck.add(key);
			}
			persistentGetters[prio].add(getter);
			persistentKeys[prio].add(finalKeysToCheck.toArray(new Key[finalKeysToCheck.size()]));
			persistentSchedulers[prio].add(sched);
			persistentCheckerItems[prio].add(item);
			persistentBlockSets[prio].add(blocks);
			trimPersistentQueue(prio, container);
			notifyAll();
		}
	}

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
		synchronized(this) {
			while(true) {
				for(short prio = 0;prio<transientKeys.length;prio++) {
					if(!transientKeys[prio].isEmpty()) {
						keys = transientKeys[prio].remove(0);
						getter = transientGetters[prio].remove(0);
						persistent = false;
						item = null;
						blocks = transientBlockSets[prio].remove(0);
						if(logMINOR)
							Logger.minor(this, "Checking transient request "+getter+" prio "+prio+" of "+transientKeys[prio].size());
						break;
					} else if((!notPersistent) && (!persistentGetters[prio].isEmpty())) {
						keys = persistentKeys[prio].remove(0);
						getter = persistentGetters[prio].remove(0);
						persistent = true;
						sched = persistentSchedulers[prio].remove(0);
						item = persistentCheckerItems[prio].remove(0);
						blocks = persistentBlockSets[prio].remove(0);
						if(logMINOR)
							Logger.minor(this, "Checking persistent request at prio "+prio);
						break;
					}
				}
				if(keys == null) {
					try {
						context.jobRunner.queue(loader, NativeThread.HIGH_PRIORITY, true);
					} catch (DatabaseDisabledException e1) {
						// Ignore
					}
					if(logMINOR) Logger.minor(this, "Waiting for more persistent requests");
					try {
						wait(100*1000);
					} catch (InterruptedException e) {
						// Ok
					}
					continue;
				}
				break;
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
			KeyBlock block = null;
			if(blocks != null)
				block = blocks.get(key);
			if(blocks == null)
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

	public int getPriority() {
		return NativeThread.NORM_PRIORITY;
	}

	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing DatastoreChecker in database", new Exception("error"));
		return false;
	}

	public void removeRequest(SendableGet request, boolean persistent, ObjectContainer container, ClientContext context, short prio) {
		if(logMINOR) Logger.minor(this, "Removing request prio="+prio+" persistent="+persistent);
		if(!persistent) {
			synchronized(this) {
				int index = transientGetters[prio].indexOf(request);
				if(index == -1) return;
				transientGetters[prio].remove(index);
				transientKeys[prio].remove(index);
				transientBlockSets[prio].remove(index);
				if(logMINOR) Logger.minor(this, "Removed transient request");
			}
		} else {
			synchronized(this) {
				int index = persistentGetters[prio].indexOf(request);
				if(index != -1) {
					persistentKeys[prio].remove(index);
					persistentGetters[prio].remove(index);
					persistentSchedulers[prio].remove(index);
					persistentCheckerItems[prio].remove(index);
					persistentBlockSets[prio].remove(index);
				}
			}
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
