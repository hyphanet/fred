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
	public void register(final SendableRequest req, boolean onDatabaseThread, RegisterMe reg) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Registering "+req, new Exception("debug"));
		final boolean persistent = req.persistent();
		if(isInsertScheduler != (req instanceof SendableInsert))
			throw new IllegalArgumentException("Expected a SendableInsert: "+req);
		if(req instanceof SendableGet) {
			final SendableGet getter = (SendableGet)req;
			
			if(persistent && onDatabaseThread) {
				schedCore.addPendingKeys(getter, selectorContainer);
				if(reg == null)
					reg = schedCore.queueRegister(getter, databaseExecutor, selectorContainer);
				final RegisterMe regme = reg;
				final Object[] keyTokens = getter.sendableKeys(selectorContainer);
				final ClientKey[] keys = new ClientKey[keyTokens.length];
				for(int i=0;i<keyTokens.length;i++) {
					keys[i] = getter.getKey(keyTokens[i], selectorContainer);
					selectorContainer.activate(keys[i], 5);
				}
				final BlockSet blocks = getter.getContext().blocks;
				final boolean dontCache = getter.dontCache();
				datastoreCheckerExecutor.execute(new Runnable() {

					public void run() {
						registerCheckStore(getter, true, keyTokens, keys, regme, blocks, dontCache);
					}
					
				}, getter.getPriorityClass(selectorContainer), "Checking datastore");
			} else if(persistent) {
				final RegisterMe regme = reg;
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						container.activate(getter, 1);
						schedCore.addPendingKeys(getter, container);
						RegisterMe reg = regme;
						if(reg == null)
							reg = schedCore.queueRegister(getter, databaseExecutor, container);
						final RegisterMe regInner = reg;
						final Object[] keyTokens = getter.sendableKeys(container);
						final ClientKey[] keys = new ClientKey[keyTokens.length];
						for(int i=0;i<keyTokens.length;i++) {
							keys[i] = getter.getKey(keyTokens[i], selectorContainer);
							container.activate(keys[i], 5);
						}
						final BlockSet blocks = getter.getContext().blocks;
						final boolean dontCache = getter.dontCache();
						datastoreCheckerExecutor.execute(new Runnable() {

							public void run() {
								registerCheckStore(getter, true, keyTokens, keys, regInner, blocks, dontCache);
							}
							
						}, getter.getPriorityClass(container), "Checking datastore");
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
						registerCheckStore(getter, false, keyTokens, keys, null, getter.getContext().blocks, getter.dontCache());
					}
					
				}, getter.getPriorityClass(null), "Checking datastore");
			}
		} else {
			if(persistent) {
				if(onDatabaseThread) {
					schedCore.queueRegister(req, databaseExecutor, selectorContainer);
					finishRegister(req, persistent, false, true, reg);
				} else {
					final RegisterMe regme = reg;
					jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							container.activate(req, 1);
							RegisterMe reg = regme;
							if(reg == null)
								reg = schedCore.queueRegister(req, databaseExecutor, selectorContainer);
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
	 * @param getter The SendableGet. NOTE: If persistent, DO NOT USE THIS INLINE, because it won't
	 * be activated. This is why we pass in extraBlocks and dontCache.
	 * @param reg 
	 */
	protected void registerCheckStore(SendableGet getter, boolean persistent, Object[] keyTokens, ClientKey[] keys, RegisterMe reg, BlockSet extraBlocks, boolean dontCache) {
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
					if(extraBlocks != null)
						block = extraBlocks.get(key);
					if(block == null)
						block = node.fetchKey(key, dontCache);
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
					getter.onFailure(new LowLevelGetException(LowLevelGetException.DECODE_FAILED), tok, null, clientContext);
				else {
					final SendableGet g = getter;
					final Object token = tok;
					jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							container.activate(g, 1);
							g.onFailure(new LowLevelGetException(LowLevelGetException.DECODE_FAILED), token, container, context);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
				}
				continue; // other keys might be valid
			}
			if(block != null) {
				if(logMINOR) Logger.minor(this, "Can fulfill "+getter+" ("+tok+") immediately from store");
				if(!persistent)
					getter.onSuccess(block, true, tok, null, clientContext);
				else {
					final ClientKeyBlock b = block;
					final Object t = tok;
					final SendableGet g = getter;
					if(persistent) {
						jobRunner.queue(new DBJob() {
							
							public void run(ObjectContainer container, ClientContext context) {
								container.activate(g, 1);
								g.onSuccess(b, true, t, container, context);
							}
							
						}, NativeThread.NORM_PRIORITY, false);
					} else {
						g.onSuccess(b, true, t, null, clientContext);
					}
				}
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
				if(persistent)
					selectorContainer.activate(req, 1);
				if(logMINOR)
					Logger.minor(this, "finishRegister() for "+req);
				if(anyValid)
					schedCore.innerRegister(req, random, selectorContainer);
				selectorContainer.delete(reg);
				maybeFillStarterQueue(selectorContainer, clientContext);
				starter.wakeUp();
			} else {
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						container.activate(req, 1);
						if(logMINOR)
							Logger.minor(this, "finishRegister() for "+req);
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
	
	private synchronized ChosenRequest removeFirst(ObjectContainer container, boolean transientOnly, boolean notTransient) {
		if(!databaseExecutor.onThread()) {
			throw new IllegalStateException("Not on database thread!");
		}
		short fuzz = -1;
		if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
			fuzz = -1;
		else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
			fuzz = 0;	
		// schedCore juggles both
		return schedCore.removeFirst(fuzz, random, offeredKeys, starter, schedTransient, transientOnly, notTransient, Short.MAX_VALUE, Short.MAX_VALUE, clientContext, container);
	}

	public ChosenRequest getBetterNonPersistentRequest(ChosenRequest req) {
		short fuzz = -1;
		if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
			fuzz = -1;
		else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
			fuzz = 0;	
		if(req == null)
			return schedCore.removeFirst(fuzz, random, offeredKeys, starter, schedTransient, true, false, Short.MAX_VALUE, Integer.MAX_VALUE, clientContext, null);
		short prio = req.prio;
		int retryCount = req.request.getRetryCount();
		return schedCore.removeFirst(fuzz, random, offeredKeys, starter, schedTransient, true, false, prio, retryCount, clientContext, null);
	}
	
	/** The maximum number of requests that we will keep on the in-RAM request
	 * starter queue. */
	static final int MAX_STARTER_QUEUE_SIZE = 100;
	
	/** The above doesn't include in-flight requests. In-flight requests will
	 * of course still have PersistentChosenRequest's in the database (on disk)
	 * even though they are not on the starter queue and so don't count towards
	 * the above limit. So we have a higher limit before we complain that 
	 * something odd is happening.. (e.g. leaking PersistentChosenRequest's). */
	static final int WARNING_STARTER_QUEUE_SIZE = 300;
	
	/**
	 * Normally this will only contain PersistentChosenRequest's, however in the
	 * case of coalescing keys, we will put ChosenRequest's back onto it as well.
	 */
	private transient LinkedList starterQueue = new LinkedList();
	
	public LinkedList getRequestStarterQueue() {
		return starterQueue;
	}
	
	public void queueFillRequestStarterQueue() {
		jobRunner.queue(requestStarterQueueFiller, NativeThread.MAX_PRIORITY, true);
	}

	void addToStarterQueue(ChosenRequest req) {
		synchronized(starterQueue) {
			starterQueue.add(req);
		}
	}
	
	int starterQueueSize() {
		synchronized(starterQueue) {
			return starterQueue.size();
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
				int size = starterQueue.size();
				if(size >= MAX_STARTER_QUEUE_SIZE) {
					if(size >= WARNING_STARTER_QUEUE_SIZE)
						Logger.error(this, "Queue already full: "+starterQueue.size());
					return;
				}
			}
			while(true) {
				req = removeFirst(container, false, true);
				if(req == null) return;
				container.activate(req.key, 5);
				container.activate(req.ckey, 5);
				container.activate(req.request, 1);
				container.activate(req.request.getClientRequest(), 1);
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
	
	public void removePendingKey(final SendableGet getter, final boolean complain, final Key key, ObjectContainer container) {
		if(!getter.persistent()) {
			boolean dropped = schedTransient.removePendingKey(getter, complain, key, container);
			if(dropped && offeredKeys != null && !node.peersWantKey(key)) {
				for(int i=0;i<offeredKeys.length;i++)
					offeredKeys[i].remove(key);
			}
			if(transientCooldownQueue != null)
				transientCooldownQueue.removeKey(key, getter, getter.getCooldownWakeupByKey(key, null), null);
		} else if(container != null) {
			// We are on the database thread already.
			schedCore.removePendingKey(getter, complain, key, container);
			if(persistentCooldownQueue != null)
				persistentCooldownQueue.removeKey(key, getter, getter.getCooldownWakeupByKey(key, container), container);
		} else {
			jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					container.activate(getter, 1);
					schedCore.removePendingKey(getter, complain, key, container);
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
			removePendingKey(getter, complain, ckey.getNodeKey(), container);
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
		if(req.isPersistent()) {
			jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					schedCore.succeeded(succeeded, container);
					container.delete((PersistentChosenRequest)req);
				}
				
			}, NativeThread.HIGH_PRIORITY-1, false);
			// Boost the priority so the PersistentChosenRequest gets deleted reasonably quickly.
		} else
			schedTransient.succeeded(succeeded, null);
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
							transientGets[i].onGotKey(key, block, null, clientContext);
						} catch (Throwable t) {
							Logger.error(this, "Caught "+t+" running callback "+transientGets[i]+" for "+key, t);
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
				container.activate(key, 1);
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
						container.activate(gets[i], 1);
						gets[i].onGotKey(key, block, container, context);
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t+" running callback "+gets[i]+" for "+key, t);
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
		priority = schedTransient.getKeyPrio(key, priority, null);
		if(priority < Short.MAX_VALUE) {
			offeredKeys[priority].queueKey(key);
			starter.wakeUp();
		}
		
		final short oldPrio = priority;
		
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				container.activate(key, 1);
				short priority = schedCore.getKeyPrio(key, oldPrio, container);
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
		moveKeysFromCooldownQueue(transientCooldownQueue, false, null);
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				if(moveKeysFromCooldownQueue(persistentCooldownQueue, true, selectorContainer))
					starter.wakeUp();
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}
	
	private boolean moveKeysFromCooldownQueue(CooldownQueue queue, boolean persistent, ObjectContainer container) {
		if(queue == null) return false;
		long now = System.currentTimeMillis();
		/*
		 * Only go around once. We will be called again. If there are keys to move, then RequestStarter will not
		 * sleep, because it will start them. Then it will come back here. If we are off-thread i.e. on the database
		 * thread, then we will wake it up if we find keys... and we'll be scheduled again.
		 */
		final int MAX_KEYS = 20;
		boolean found = false;
		Key[] keys = queue.removeKeyBefore(now, container, MAX_KEYS);
		if(keys == null) return false;
		found = true;
		for(int j=0;j<keys.length;j++) {
			Key key = keys[j];
			if(persistent)
				container.activate(key, 5);
			if(logMINOR) Logger.minor(this, "Restoring key: "+key);
			SendableGet[] gets = schedCore.getClientsForPendingKey(key);
			SendableGet[] transientGets = schedTransient.getClientsForPendingKey(key);
			if(gets == null && transientGets == null) {
				// Not an error as this can happen due to race conditions etc.
				if(logMINOR) Logger.minor(this, "Restoring key but no keys queued?? for "+key);
				continue;
			} else {
				if(gets != null)
				for(int i=0;i<gets.length;i++) {
					if(persistent)
						container.activate(gets[i], 1);
					gets[i].requeueAfterCooldown(key, now, container, clientContext);
				}
				if(transientGets != null)
				for(int i=0;i<transientGets.length;i++)
					transientGets[i].requeueAfterCooldown(key, now, container, clientContext);
			}
		}
		return found;
	}

	public long countTransientQueuedRequests() {
		// Approximately... there might be some overlap in the two pendingKeys's...
		return schedCore.countQueuedRequests() + schedTransient.countQueuedRequests();
	}

	public KeysFetchingLocally fetchingKeys() {
		return schedCore;
	}

	public void removeFetchingKey(Key key, ChosenRequest req) {
		schedCore.removeFetchingKey(key, req);
	}

	public void callFailure(final SendableGet get, final LowLevelGetException e, final Object keyNum, int prio, final ChosenRequest req) {
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				container.activate(get, 1);
				get.onFailure(e, keyNum, selectorContainer, clientContext);
				if(get.persistent()) {
					if(logMINOR)
						Logger.minor(this, "Deleting "+req);
					selectorContainer.delete((PersistentChosenRequest)req);
				}
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}
	
	public void callFailure(final SendableInsert put, final LowLevelPutException e, final Object keyNum, int prio, final ChosenRequest req) {
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				container.activate(put, 1);
				put.onFailure(e, keyNum, selectorContainer, clientContext);
				if(put.persistent()) {
					if(logMINOR)
						Logger.minor(this, "Deleting "+req);
					selectorContainer.delete((PersistentChosenRequest)req);
				}
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}

	public void callSuccess(final SendableInsert put, final Object keyNum, int prio, final ChosenRequest req) {
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				container.activate(put, 1);
				put.onSuccess(keyNum, selectorContainer, clientContext);
				if(put.persistent()) {
					if(logMINOR)
						Logger.minor(this, "Deleting "+req);
					selectorContainer.delete((PersistentChosenRequest)req);
				}
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}

	public FECQueue getFECQueue() {
		return clientContext.fecQueue;
	}

	public ClientContext getContext() {
		return clientContext;
	}

	/**
	 * @return True unless the key was already present.
	 */
	public boolean addToFetching(Key key) {
		return schedCore.addToFetching(key);
	}

	public void requeue(final ChosenRequest req) {
		if(req.isPersistent()) {
			this.clientContext.jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					container.activate(req.request, 1);
					if(req.request.isCancelled(container)) {
						container.delete(req);
						return;
					}
					addToStarterQueue(req);
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
		} else {
			if(req.request.isCancelled(null)) return;
			addToStarterQueue(req);
		}
	}
	
	
}
