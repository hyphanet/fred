/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.LinkedList;

import com.db4o.ObjectContainer;

import freenet.client.FECQueue;
import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.LowLevelPutException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.support.Logger;
import freenet.support.PrioritizedSerialExecutor;
import freenet.support.api.StringCallback;
import freenet.support.io.NativeThread;

/**
 * Every X seconds, the RequestSender calls the ClientRequestScheduler to
 * ask for a request to start. A request is then started, in its own 
 * thread. It is removed at that point.
 */
public class ClientRequestScheduler implements RequestScheduler {
	
	private final ClientRequestSchedulerCore schedCore;
	private final ClientRequestSchedulerNonPersistent schedTransient;
	
	private static boolean logMINOR;
	
	public static class PrioritySchedulerCallback implements StringCallback, EnumerableOptionCallback {
		final ClientRequestScheduler cs;
		private final String[] possibleValues = new String[]{ ClientRequestScheduler.PRIORITY_HARD, ClientRequestScheduler.PRIORITY_SOFT };
		
		PrioritySchedulerCallback(ClientRequestScheduler cs){
			this.cs = cs;
		}
		
		public String get(){
			if(cs != null)
				return cs.getChoosenPriorityScheduler();
			else
				return ClientRequestScheduler.PRIORITY_HARD;
		}
		
		public void set(String val) throws InvalidConfigValueException{
			String value;
			if(val == null || val.equalsIgnoreCase(get())) return;
			if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_HARD)){
				value = ClientRequestScheduler.PRIORITY_HARD;
			}else if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_SOFT)){
				value = ClientRequestScheduler.PRIORITY_SOFT;
			}else{
				throw new InvalidConfigValueException("Invalid priority scheme");
			}
			cs.setPriorityScheduler(value);
		}
		
		public String[] getPossibleValues() {
			return possibleValues;
		}
		
		public void setPossibleValues(String[] val) {
			throw new NullPointerException("Should not happen!");
		}
	}
	
	/** Long-lived container for use by the selector thread.
	 * We commit when we move a request to a lower retry level.
	 * We need to refresh objects when we activate them.
	 */
	final ObjectContainer selectorContainer;
	
	/** This DOES NOT PERSIST */
	private final OfferedKeysList[] offeredKeys;
	// we have one for inserts and one for requests
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	final RandomSource random;
	private final RequestStarter starter;
	private final Node node;
	public final String name;
	private final CooldownQueue transientCooldownQueue;
	private final CooldownQueue persistentCooldownQueue;
	final PrioritizedSerialExecutor databaseExecutor;
	final PrioritizedSerialExecutor datastoreCheckerExecutor;
	public final ClientContext clientContext;
	final DBJobRunner jobRunner;
	
	public static final String PRIORITY_NONE = "NONE";
	public static final String PRIORITY_SOFT = "SOFT";
	public static final String PRIORITY_HARD = "HARD";
	private String choosenPriorityScheduler; 
	
	public ClientRequestScheduler(boolean forInserts, boolean forSSKs, RandomSource random, RequestStarter starter, Node node, NodeClientCore core, SubConfig sc, String name, ClientContext context) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		this.selectorContainer = node.db;
		schedCore = ClientRequestSchedulerCore.create(node, forInserts, forSSKs, selectorContainer, COOLDOWN_PERIOD, core.clientDatabaseExecutor, this, context);
		schedTransient = new ClientRequestSchedulerNonPersistent(this);
		schedCore.fillStarterQueue(selectorContainer);
		schedCore.start(core);
		persistentCooldownQueue = schedCore.persistentCooldownQueue;
		this.databaseExecutor = core.clientDatabaseExecutor;
		this.datastoreCheckerExecutor = core.datastoreCheckerExecutor;
		this.starter = starter;
		this.random = random;
		this.node = node;
		this.clientContext = context;
		
		this.name = name;
		sc.register(name+"_priority_policy", PRIORITY_HARD, name.hashCode(), true, false,
				"RequestStarterGroup.scheduler"+(forSSKs?"SSK" : "CHK")+(forInserts?"Inserts":"Requests"),
				"RequestStarterGroup.schedulerLong",
				new PrioritySchedulerCallback(this));
		
		this.choosenPriorityScheduler = sc.getString(name+"_priority_policy");
		if(!forInserts) {
			offeredKeys = new OfferedKeysList[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
			for(short i=0;i<RequestStarter.NUMBER_OF_PRIORITY_CLASSES;i++)
				offeredKeys[i] = new OfferedKeysList(core, random, i);
		} else {
			offeredKeys = null;
		}
		if(!forInserts)
			transientCooldownQueue = new RequestCooldownQueue(COOLDOWN_PERIOD);
		else
			transientCooldownQueue = null;
		jobRunner = clientContext.jobRunner;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	/** Called by the  config. Callback
	 * 
	 * @param val
	 */
	protected synchronized void setPriorityScheduler(String val){
		choosenPriorityScheduler = val;
	}
	
	public void register(final SendableRequest req) {
		register(req, databaseExecutor.onThread(), null);
	}
	
	/**
	 * Register and then delete the RegisterMe which is passed in to avoid querying.
	 */
	public void register(final SendableRequest req, boolean onDatabaseThread, final RegisterMe reg) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Registering "+req, new Exception("debug"));
		final boolean persistent = req.persistent();
		if(isInsertScheduler != (req instanceof SendableInsert))
			throw new IllegalArgumentException("Expected a SendableInsert: "+req);
		if(req instanceof SendableGet) {
			final SendableGet getter = (SendableGet)req;
			
			if(persistent && onDatabaseThread) {
				schedCore.addPendingKeys(getter, selectorContainer);
				schedCore.queueRegister(getter, databaseExecutor, selectorContainer);
				final Object[] keyTokens = getter.sendableKeys(selectorContainer);
				final ClientKey[] keys = new ClientKey[keyTokens.length];
				for(int i=0;i<keyTokens.length;i++)
					keys[i] = getter.getKey(keyTokens[i], selectorContainer);
				datastoreCheckerExecutor.execute(new Runnable() {

					public void run() {
						registerCheckStore(getter, true, keyTokens, keys, reg);
					}
					
				}, getter.getPriorityClass(), "Checking datastore");
			} else if(persistent) {
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						schedCore.addPendingKeys(getter, container);
						schedCore.queueRegister(getter, databaseExecutor, container);
						final Object[] keyTokens = getter.sendableKeys(container);
						final ClientKey[] keys = new ClientKey[keyTokens.length];
						for(int i=0;i<keyTokens.length;i++)
							keys[i] = getter.getKey(keyTokens[i], container);
						datastoreCheckerExecutor.execute(new Runnable() {

							public void run() {
								registerCheckStore(getter, true, keyTokens, keys, reg);
							}
							
						}, getter.getPriorityClass(), "Checking datastore");
					}
					
				}, NativeThread.NORM_PRIORITY, false);
			} else {
				// Not persistent
				schedTransient.addPendingKeys(getter, null);
				// Check the store off-thread anyway.
				final Object[] keyTokens = getter.sendableKeys(null);
				final ClientKey[] keys = new ClientKey[keyTokens.length];
				for(int i=0;i<keyTokens.length;i++)
					keys[i] = getter.getKey(keyTokens[i], null);
				datastoreCheckerExecutor.execute(new Runnable() {

					public void run() {
						registerCheckStore(getter, false, keyTokens, keys, null);
					}
					
				}, getter.getPriorityClass(), "Checking datastore");
			}
		} else {
			if(persistent) {
				if(onDatabaseThread) {
					schedCore.queueRegister(req, databaseExecutor, selectorContainer);
					finishRegister(req, persistent, false, true, reg);
				} else {
					jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							schedCore.queueRegister(req, databaseExecutor, selectorContainer);
							// Pretend to not be on the database thread.
							// In some places (e.g. SplitFileInserter.start(), we call register() *many* times within a single transaction.
							// We can greatly improve responsiveness at the cost of some throughput and RAM by only adding the tags at this point.
							finishRegister(req, persistent, false, true, reg);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
				}
			} else {
				finishRegister(req, persistent, false, true, reg);
			}
		}
	}

	/**
	 * Check the store for all the keys on the SendableGet. By now the pendingKeys will have
	 * been set up, and this is run on the datastore checker thread. Once completed, this should
	 * (for a persistent request) queue a job on the databaseExecutor and (for a transient 
	 * request) finish registering the request immediately.
	 * @param getter
	 * @param reg 
	 */
	protected void registerCheckStore(SendableGet getter, boolean persistent, Object[] keyTokens, ClientKey[] keys, RegisterMe reg) {
		boolean anyValid = false;
		for(int i=0;i<keyTokens.length;i++) {
			Object tok = keyTokens[i];
			ClientKeyBlock block = null;
			try {
				ClientKey key = keys[i];
				if(key == null) {
					if(logMINOR)
						Logger.minor(this, "No key for "+tok+" for "+getter+" - already finished?");
					continue;
				} else {
					if(getter.getContext().blocks != null)
						block = getter.getContext().blocks.get(key);
					if(block == null)
						block = node.fetchKey(key, getter.dontCache());
					if(block == null) {
						if(!persistent) {
							schedTransient.addPendingKey(key, getter);
						} // If persistent, when it is registered (in a later job) the keys will be added first.
					} else {
						if(logMINOR)
							Logger.minor(this, "Got "+block);
					}
				}
			} catch (KeyVerifyException e) {
				// Verify exception, probably bogus at source;
				// verifies at low-level, but not at decode.
				if(logMINOR)
					Logger.minor(this, "Decode failed: "+e, e);
				if(!persistent)
					getter.onFailure(new LowLevelGetException(LowLevelGetException.DECODE_FAILED), tok, this, null, clientContext);
				else {
					final SendableGet g = getter;
					final Object token = tok;
					jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							g.onFailure(new LowLevelGetException(LowLevelGetException.DECODE_FAILED), token, ClientRequestScheduler.this, container, context);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
				}
				continue; // other keys might be valid
			}
			if(block != null) {
				if(logMINOR) Logger.minor(this, "Can fulfill "+getter+" ("+tok+") immediately from store");
				if(!persistent)
					getter.onSuccess(block, true, tok, this, null, clientContext);
				else {
					final ClientKeyBlock b = block;
					final Object t = tok;
					final SendableGet g = getter;
					if(persistent) {
						jobRunner.queue(new DBJob() {
							
							public void run(ObjectContainer container, ClientContext context) {
								g.onSuccess(b, true, t, ClientRequestScheduler.this, container, context);
							}
							
						}, NativeThread.NORM_PRIORITY, false);
					} else {
						g.onSuccess(b, true, t, ClientRequestScheduler.this, null, clientContext);
					}
				}
				// Even with working thread priorities, we still get very high latency accessing
				// the datastore when background threads are doing it in parallel.
				// So yield() here, unless priority is very high.
				if(getter.getPriorityClass() > RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS)
					Thread.yield();
			} else {
				anyValid = true;
			}
		}
		finishRegister(getter, persistent, false, anyValid, reg);
	}

	private void finishRegister(final SendableRequest req, boolean persistent, boolean onDatabaseThread, final boolean anyValid, final RegisterMe reg) {
		if(persistent) {
			// Add to the persistent registration queue
			if(onDatabaseThread) {
				if(!databaseExecutor.onThread()) {
					throw new IllegalStateException("Not on database thread!");
				}
				if(anyValid)
					schedCore.innerRegister(req, random, selectorContainer);
				selectorContainer.delete(reg);
				maybeFillStarterQueue(selectorContainer, clientContext);
				starter.wakeUp();
			} else {
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						if(anyValid)
							schedCore.innerRegister(req, random, container);
						container.delete(reg);
						maybeFillStarterQueue(container, context);
						starter.wakeUp();
					}
					
				}, NativeThread.NORM_PRIORITY, false);
			}
		} else {
			// Register immediately.
			schedTransient.innerRegister(req, random, null);
			starter.wakeUp();
		}
	}

	private void maybeFillStarterQueue(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(starterQueue.size() > MAX_STARTER_QUEUE_SIZE / 2)
				return;
		}
		requestStarterQueueFiller.run(container, context);
	}

	void addPendingKey(final ClientKey key, final SendableGet getter) {
		if(getter.persistent()) {
			if(!databaseExecutor.onThread()) {
				throw new IllegalStateException("Not on database thread!");
			}
			schedCore.addPendingKey(key, getter);
		} else
			schedTransient.addPendingKey(key, getter);
	}
	
	private synchronized ChosenRequest removeFirst(ObjectContainer container) {
		if(!databaseExecutor.onThread()) {
			throw new IllegalStateException("Not on database thread!");
		}
		short fuzz = -1;
		if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
			fuzz = -1;
		else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
			fuzz = 0;	
		// schedCore juggles both
		return schedCore.removeFirst(fuzz, random, offeredKeys, starter, schedTransient, false, Short.MAX_VALUE, Short.MAX_VALUE, clientContext, container);
	}

	public ChosenRequest getBetterNonPersistentRequest(ChosenRequest req) {
		short fuzz = -1;
		if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
			fuzz = -1;
		else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
			fuzz = 0;	
		if(req == null)
			return schedCore.removeFirst(fuzz, random, offeredKeys, starter, schedTransient, true, Short.MAX_VALUE, Integer.MAX_VALUE, clientContext, null);
		short prio = req.request.getPriorityClass();
		int retryCount = req.request.getRetryCount();
		return schedCore.removeFirst(fuzz, random, offeredKeys, starter, schedTransient, true, prio, retryCount, clientContext, null);
	}
	
	private static final int MAX_STARTER_QUEUE_SIZE = 100;
	
	private transient LinkedList starterQueue = new LinkedList();
	
	public LinkedList getRequestStarterQueue() {
		return starterQueue;
	}
	
	public void queueFillRequestStarterQueue() {
		jobRunner.queue(requestStarterQueueFiller, NativeThread.MAX_PRIORITY, true);
	}

	void addToStarterQueue(PersistentChosenRequest req) {
		synchronized(starterQueue) {
			starterQueue.add(req);
		}
	}
	
	private DBJob requestStarterQueueFiller = new DBJob() {
		public void run(ObjectContainer container, ClientContext context) {
			if(isInsertScheduler && !isSSKScheduler) {
				if(logMINOR) Logger.minor(this, "Scheduling inserts...");
			}
			if(logMINOR) Logger.minor(this, "Filling request queue... (SSK="+isSSKScheduler+" insert="+isInsertScheduler);
			ChosenRequest req = null;
			synchronized(starterQueue) {
				if(starterQueue.size() >= MAX_STARTER_QUEUE_SIZE) {
					Logger.error(this, "Queue already full: "+starterQueue.size());
					return;
				}
			}
			while(true) {
				req = removeFirst(container);
				if(req == null) return;
				synchronized(starterQueue) {
					if(req != null) {
						starterQueue.add(req);
						if(logMINOR)
							Logger.minor(this, "Added to starterQueue: "+req+" size now "+starterQueue.size());
						req = null;
					}
					if(starterQueue.size() >= MAX_STARTER_QUEUE_SIZE) return;
				}
			}
		}
	};
	
	public void removePendingKey(final SendableGet getter, final boolean complain, final Key key) {
		if(getter.persistent()) {
			boolean dropped = schedTransient.removePendingKey(getter, complain, key);
			if(dropped && offeredKeys != null && !node.peersWantKey(key)) {
				for(int i=0;i<offeredKeys.length;i++)
					offeredKeys[i].remove(key);
			}
			if(transientCooldownQueue != null)
				transientCooldownQueue.removeKey(key, getter, getter.getCooldownWakeupByKey(key, null), null);
		} else {
			jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					schedCore.removePendingKey(getter, complain, key);
					if(persistentCooldownQueue != null)
						persistentCooldownQueue.removeKey(key, getter, getter.getCooldownWakeupByKey(key, container), container);
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		}
	}
	
	/**
	 * Remove a SendableGet from the list of getters we maintain for each key, indicating that we are no longer interested
	 * in that key.
	 * @param getter
	 * @param complain
	 */
	public void removePendingKeys(SendableGet getter, boolean complain) {
		ObjectContainer container;
		if(getter.persistent()) {
			container = selectorContainer;
			if(!databaseExecutor.onThread()) {
				throw new IllegalStateException("Not on database thread!");
			}
		} else {
			container = null;
		}
		Object[] keyTokens = getter.allKeys(container);
		for(int i=0;i<keyTokens.length;i++) {
			Object tok = keyTokens[i];
			ClientKey ckey = getter.getKey(tok, container);
			if(ckey == null) {
				if(complain)
					Logger.error(this, "Key "+tok+" is null for "+getter, new Exception("debug"));
				continue;
			}
			removePendingKey(getter, complain, ckey.getNodeKey());
		}
	}

	public void reregisterAll(final ClientRequester request, ObjectContainer container) {
		schedTransient.reregisterAll(request, random, this, container);
		starter.wakeUp();
	}
	
	public String getChoosenPriorityScheduler() {
		return choosenPriorityScheduler;
	}

	public synchronized void succeeded(final BaseSendableGet succeeded, final ChosenRequest req) {
		if(succeeded.persistent()) {
			jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					schedCore.succeeded(succeeded);
					if(succeeded.persistent())
						container.delete((PersistentChosenRequest)req);
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} else
			schedTransient.succeeded(succeeded);
	}

	public void tripPendingKey(final KeyBlock block) {
		if(logMINOR) Logger.minor(this, "tripPendingKey("+block.getKey()+")");
		
		// First the transient stuff
		
		if(offeredKeys != null) {
			for(int i=0;i<offeredKeys.length;i++) {
				offeredKeys[i].remove(block.getKey());
			}
		}
		final Key key = block.getKey();
		final SendableGet[] transientGets = schedTransient.removePendingKey(key);
		if(transientGets != null && transientGets.length > 0) {
			node.executor.execute(new Runnable() {
				public void run() {
					if(logMINOR) Logger.minor(this, "Running "+transientGets.length+" callbacks off-thread for "+block.getKey());
					for(int i=0;i<transientGets.length;i++) {
						try {
							if(logMINOR) Logger.minor(this, "Calling callback for "+transientGets[i]+" for "+key);
							transientGets[i].onGotKey(key, block, ClientRequestScheduler.this, null, clientContext);
						} catch (Throwable t) {
							Logger.error(this, "Caught "+t+" running callback "+transientGets[i]+" for "+key);
						}
					}
				}
			}, "Running off-thread callbacks for "+block.getKey());
			if(transientCooldownQueue != null) {
				for(int i=0;i<transientGets.length;i++)
					transientCooldownQueue.removeKey(key, transientGets[i], transientGets[i].getCooldownWakeupByKey(key, null), null);
			}
		}
		
		// Now the persistent stuff
		
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				final SendableGet[] gets = schedCore.removePendingKey(key);
				if(gets == null) return;
				if(persistentCooldownQueue != null) {
					for(int i=0;i<gets.length;i++)
						persistentCooldownQueue.removeKey(key, gets[i], gets[i].getCooldownWakeupByKey(key, container), container);
				}
				// Call the callbacks on the database executor thread, because the first thing
				// they will need to do is access the database to decide whether they need to
				// decode, and if so to find the key to decode with.
				for(int i=0;i<gets.length;i++) {
					try {
						if(logMINOR) Logger.minor(this, "Calling callback for "+gets[i]+" for "+key);
						gets[i].onGotKey(key, block, ClientRequestScheduler.this, container, context);
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t+" running callback "+gets[i]+" for "+key);
					}
				}
				if(logMINOR) Logger.minor(this, "Finished running callbacks");
			}
			
		}, NativeThread.NORM_PRIORITY, false);
		
	}

	/** If we want the offered key, or if force is enabled, queue it */
	public void maybeQueueOfferedKey(final Key key, boolean force) {
		if(logMINOR)
			Logger.minor(this, "maybeQueueOfferedKey("+key+","+force);
		short priority = Short.MAX_VALUE;
		if(force) {
			// FIXME what priority???
			priority = RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
		}
		priority = schedTransient.getKeyPrio(key, priority);
		if(priority < Short.MAX_VALUE) {
			offeredKeys[priority].queueKey(key);
			starter.wakeUp();
		}
		
		final short oldPrio = priority;
		
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				short priority = schedCore.getKeyPrio(key, oldPrio);
				if(priority >= oldPrio) return; // already on list at >= priority
				offeredKeys[priority].queueKey(key);
				starter.wakeUp();
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}

	public void dequeueOfferedKey(Key key) {
		for(int i=0;i<offeredKeys.length;i++) {
			offeredKeys[i].remove(key);
		}
	}

	/**
	 * MUST be called from database thread!
	 */
	public long queueCooldown(ClientKey key, SendableGet getter) {
		if(getter.persistent())
			return persistentCooldownQueue.add(key.getNodeKey(), getter, selectorContainer);
		else
			return transientCooldownQueue.add(key.getNodeKey(), getter, null);
	}

	public void moveKeysFromCooldownQueue() {
		moveKeysFromCooldownQueue(transientCooldownQueue, null);
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				if(moveKeysFromCooldownQueue(persistentCooldownQueue, selectorContainer))
					starter.wakeUp();
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}
	
	private boolean moveKeysFromCooldownQueue(CooldownQueue queue, ObjectContainer container) {
		if(queue == null) return false;
		long now = System.currentTimeMillis();
		final int MAX_KEYS = 1024;
		boolean found = false;
		while(true) {
		Key[] keys = queue.removeKeyBefore(now, container, MAX_KEYS);
		if(keys == null) return found;
		found = true;
		for(int j=0;j<keys.length;j++) {
			Key key = keys[j];
			if(logMINOR) Logger.minor(this, "Restoring key: "+key);
			SendableGet[] gets = schedCore.getClientsForPendingKey(key);
			SendableGet[] transientGets = schedTransient.getClientsForPendingKey(key);
			if(gets == null && transientGets == null) {
				// Not an error as this can happen due to race conditions etc.
				if(logMINOR) Logger.minor(this, "Restoring key but no keys queued?? for "+key);
				continue;
			} else {
				if(gets != null)
				for(int i=0;i<gets.length;i++)
					gets[i].requeueAfterCooldown(key, now, container, clientContext);
				if(transientGets != null)
				for(int i=0;i<transientGets.length;i++)
					transientGets[i].requeueAfterCooldown(key, now, container, clientContext);
			}
		}
		if(keys.length < MAX_KEYS) return found;
		}
	}

	public long countTransientQueuedRequests() {
		// Approximately... there might be some overlap in the two pendingKeys's...
		return schedCore.countQueuedRequests() + schedTransient.countQueuedRequests();
	}

	public KeysFetchingLocally fetchingKeys() {
		return schedCore;
	}

	public void removeFetchingKey(Key key) {
		schedCore.removeFetchingKey(key);
	}

	public PrioritizedSerialExecutor getDatabaseExecutor() {
		return databaseExecutor;
	}

	public void callFailure(final SendableGet get, final LowLevelGetException e, final Object keyNum, int prio, String name, final ChosenRequest req) {
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				get.onFailure(e, keyNum, ClientRequestScheduler.this, selectorContainer, clientContext);
				if(get.persistent())
					selectorContainer.delete((PersistentChosenRequest)req);
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}
	
	public void callFailure(final SendableInsert put, final LowLevelPutException e, final Object keyNum, int prio, String name, final ChosenRequest req) {
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				put.onFailure(e, keyNum, selectorContainer, clientContext);
				if(put.persistent())
					selectorContainer.delete((PersistentChosenRequest)req);
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}

	public void callSuccess(final SendableInsert put, final Object keyNum, int prio, String name, final ChosenRequest req) {
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				put.onSuccess(keyNum, selectorContainer, clientContext);
				if(put.persistent())
					selectorContainer.delete((PersistentChosenRequest)req);
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}

	public FECQueue getFECQueue() {
		return clientContext.fecQueue;
	}

	public ClientContext getContext() {
		return clientContext;
	}

}
