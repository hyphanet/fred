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
import freenet.keys.Key;
import freenet.keys.KeyBlock;
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
	
	public void registerInsert(final SendableRequest req, boolean persistent, boolean regmeOnly) {
		registerInsert(req, persistent, regmeOnly, databaseExecutor.onThread());
	}
	
	public void registerInsert(final SendableRequest req, boolean persistent, boolean regmeOnly, boolean onDatabaseThread) {
		if(persistent) {
			if(onDatabaseThread) {
				if(regmeOnly) {
					final RegisterMe regme = new RegisterMe(null, null, req, req.getPriorityClass(selectorContainer), schedCore, null);
					selectorContainer.set(regme);
					if(logMINOR)
						Logger.minor(this, "Added insert RegisterMe: "+regme);
					jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							container.delete(regme);
							container.activate(req, 1);
							registerInsert(req, true, false, true);
						}
						
					}, NativeThread.NORM_PRIORITY, false);
					return;
				}
				schedCore.innerRegister(req, random, selectorContainer);
			} else {
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						container.activate(req, 1);
						schedCore.innerRegister(req, random, selectorContainer);
					}
					
				}, NativeThread.NORM_PRIORITY, false);
			}
		} else {
			schedTransient.innerRegister(req, random, null);
		}
	}
	
	/**
	 * Register a group of requests (not inserts): a GotKeyListener and/or one 
	 * or more SendableGet's.
	 * @param listener Listeners for specific keys. Can be null if the listener
	 * is already registered e.g. most of the time with SplitFileFetcher*.
	 * @param getters The actual requests to register to the request sender queue.
	 * @param registerOffThread If true, create and store a RegisterMe to ensure
	 * that the request is registered, but then schedule a job to complete it
	 * after this job completes. Reduces the latency impact of scheduling a big
	 * splitfile dramatically.
	 * @param persistent True if the request is persistent.
	 * @param onDatabaseThread True if we are running on the database thread.
	 * NOTE: delayedStoreCheck/probablyNotInStore is unnecessary because we only
	 * register the listener once.
	 */
	public void register(final GotKeyListener listener, final SendableGet[] getters, boolean registerOffThread, final boolean persistent, boolean onDatabaseThread, final BlockSet blocks, final RegisterMe oldReg) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "register("+persistent+","+listener+","+getters+","+registerOffThread);
		if(isInsertScheduler) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			throw e;
		}
		if(persistent) {
			if(onDatabaseThread) {
				innerRegister(listener, getters, registerOffThread, persistent, blocks, oldReg);
			} else {
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						// registerOffThread would be pointless because this is a separate job.
						innerRegister(listener, getters, false, persistent, blocks, oldReg);
					}
					
				}, NativeThread.NORM_PRIORITY, false);
			}
		} else {
			if(listener != null) {
				schedTransient.addPendingKeys(listener, null);
				short prio = listener.getPriorityClass(selectorContainer);
				final Key[] keys = listener.listKeys(selectorContainer);
				final boolean dontCache = listener.dontCache(null);
				datastoreCheckerExecutor.execute(new Runnable() {

					public void run() {
						// Check the store, then queue the requests to the main queue.
						registerCheckStore(getters, false, keys, null, blocks, dontCache);
					}
					
				}, prio, "Checking datastore");
			} else {
				this.finishRegister(getters, persistent, false, true, null);
			}
		}
	}
	
	
	private void innerRegister(final GotKeyListener listener, final SendableGet[] getters, boolean registerOffThread, boolean persistent, final BlockSet blocks, RegisterMe reg) {
		if(isInsertScheduler) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			throw e;
		}
		if(listener != null) {
			if(registerOffThread) {
				short prio = listener.getPriorityClass(selectorContainer);
				if(reg == null) {
					reg = new RegisterMe(listener, getters, null, prio, schedCore, blocks);
					selectorContainer.set(reg);
				}
				final RegisterMe regme = reg;
				if(logMINOR) Logger.minor(this, "Added regme: "+regme);
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						register(listener, getters, false, true, true, blocks, regme);
					}
					
				}, NativeThread.NORM_PRIORITY, false);
				return;
			} else {
				short prio = listener.getPriorityClass(selectorContainer);
				schedCore.addPendingKeys(listener, selectorContainer);
				if(reg == null && getters != null) {
					reg = new RegisterMe(null, getters, null, prio, schedCore, blocks);
					selectorContainer.set(reg);
					if(logMINOR) Logger.minor(this, "Added regme: "+reg);
				} else {
					if(reg != null)
						selectorContainer.delete(reg);
					reg = null; // Nothing to finish registering.
				}
				final RegisterMe regme = reg;
				// Check the datastore before proceding.
				final Key[] keys = listener.listKeys(selectorContainer);
				final boolean dontCache = listener.dontCache(selectorContainer);
				datastoreCheckerExecutor.execute(new Runnable() {

					public void run() {
						// Check the store, then queue the requests to the main queue.
						registerCheckStore(getters, true, keys, regme, blocks, dontCache);
					}
					
				}, prio, "Checking datastore");

			}
		} else {
			// The listener is already registered.
			// Ignore registerOffThread for now.
			short prio = RequestStarter.MINIMUM_PRIORITY_CLASS;
			for(int i=0;i<getters.length;i++) {
				short p = getters[i].getPriorityClass(selectorContainer);
				if(p < prio) prio = p;
			}
			this.finishRegister(getters, persistent, true, true, null);
		}
	}

	protected void registerCheckStore(SendableGet[] getters, boolean persistent, 
			Key[] keys, RegisterMe regme, BlockSet extraBlocks, boolean dontCache) {
		if(isInsertScheduler && getters != null) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			throw e;
		}
		boolean anyValid = false;
		for(int i=0;i<keys.length;i++) {
			Key key = keys[i];
			KeyBlock block = null;
			if(key == null) {
				if(logMINOR) Logger.minor(this, "No key at "+i);
				continue;
			} else {
				if(extraBlocks != null)
					block = extraBlocks.get(key);
				if(block == null)
					block = node.fetch(key, dontCache);
				if(block != null) {
					if(logMINOR)
						Logger.minor(this, "Got "+block);
				}
			}
			if(block != null) {
				if(logMINOR) Logger.minor(this, "Found key");
				tripPendingKey(block);
			} else {
				anyValid = true;
			}
		}
		finishRegister(getters, persistent, false, anyValid, regme);
	}
	
	/** If enabled, if the queue is less than 25% full, attempt to add newly 
	 * registered requests directly to it, storing a PersistentChosenRequest and 
	 * bypassing registration on the queue. Risky optimisation. */
	static final boolean TRY_DIRECT = true;

	private void finishRegister(final SendableGet[] getters, boolean persistent, boolean onDatabaseThread, final boolean anyValid, final RegisterMe reg) {
		if(isInsertScheduler && getters != null) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			if(onDatabaseThread) {
				for(int i=0;i<getters.length;i++) {
					getters[i].internalError(null, e, this, selectorContainer, clientContext, persistent);
				}
			}
			throw e;
		}
		if(persistent) {
			// Add to the persistent registration queue
			if(onDatabaseThread) {
				if(!databaseExecutor.onThread()) {
					throw new IllegalStateException("Not on database thread!");
				}
				if(persistent)
					selectorContainer.activate(getters, 1);
				boolean tryDirect = false;
				if(anyValid && TRY_DIRECT) {
					synchronized(starterQueue) {
						tryDirect = starterQueue.size() < MAX_STARTER_QUEUE_SIZE * 1 / 4;
					}
					if(tryDirect) {
						for(int i=0;i<getters.length;i++) {
							SendableGet getter = getters[i];
							while(true) {
								PersistentChosenRequest cr = (PersistentChosenRequest) schedCore.maybeMakeChosenRequest(getter, selectorContainer, clientContext);
								if(cr == null) {
									if(!(getter.isEmpty(selectorContainer) || getter.isCancelled(selectorContainer)))
										// Still needs to be registered
										tryDirect = false;
									break;
								}
								synchronized(starterQueue) {
									if(logMINOR) Logger.minor(this, "Adding directly to queue: "+cr);
									starterQueue.add(cr);
									if(starterQueue.size() >= MAX_STARTER_QUEUE_SIZE) {
										if(!(getter.isEmpty(selectorContainer) || getter.isCancelled(selectorContainer)))
											// Still stuff to register.
											tryDirect = false;
										break;
									}
								}
							}
						}
					}
				}
				if(logMINOR)
					Logger.minor(this, "finishRegister() for "+getters);
				if(anyValid) {
					if(!tryDirect) {
						boolean wereAnyValid = false;
						for(int i=0;i<getters.length;i++) {
							SendableGet getter = getters[i];
							if(!(getter.isCancelled(selectorContainer) || getter.isEmpty(selectorContainer))) {
								wereAnyValid = true;
								schedCore.innerRegister(getter, random, selectorContainer);
							}
						}
						if(!wereAnyValid) {
							Logger.error(this, "No requests valid: "+getters);
						}
					}
				}
				if(reg != null)
					selectorContainer.delete(reg);
				maybeFillStarterQueue(selectorContainer, clientContext);
				starter.wakeUp();
			} else {
				jobRunner.queue(new DBJob() {

					public void run(ObjectContainer container, ClientContext context) {
						container.activate(getters, 1);
						if(logMINOR)
							Logger.minor(this, "finishRegister() for "+getters);
						boolean wereAnyValid = false;
						for(int i=0;i<getters.length;i++) {
							SendableGet getter = getters[i];
							if(!(getter.isCancelled(selectorContainer) || getter.isEmpty(selectorContainer))) {
								wereAnyValid = true;
								schedCore.innerRegister(getter, random, selectorContainer);
							}
						}
						if(!wereAnyValid) {
							Logger.error(this, "No requests valid: "+getters);
						}
						if(reg != null)
							container.delete(reg);
						maybeFillStarterQueue(container, context);
						starter.wakeUp();
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
			}
		} else {
			// Register immediately.
			for(int i=0;i<getters.length;i++)
				schedTransient.innerRegister(getters[i], random, null);
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

	void addPendingKey(final ClientKey key, final GotKeyListener getter) {
		if(getter.persistent()) {
			if(!databaseExecutor.onThread()) {
				throw new IllegalStateException("Not on database thread!");
			}
			schedCore.addPendingKey(key.getNodeKey(), getter, selectorContainer);
		} else
			schedTransient.addPendingKey(key.getNodeKey(), getter, null);
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
		synchronized(starterQueue) {
			if(starterQueue.size() > MAX_STARTER_QUEUE_SIZE / 2)
				return;
		}
		jobRunner.queue(requestStarterQueueFiller, NativeThread.MAX_PRIORITY, true);
	}

	void addToStarterQueue(ChosenRequest req) {
		synchronized(starterQueue) {
			if(starterQueue.contains(req)) {
				Logger.error(this, "Not re-adding: "+req);
				return;
			}
			starterQueue.add(req);
		}
	}
	
	int starterQueueSize() {
		synchronized(starterQueue) {
			return starterQueue.size();
		}
	}
	
	/** Maximum number of requests to select from a single SendableRequest */
	final int MAX_CONSECUTIVE_SAME_REQ = 50;
	
	private DBJob requestStarterQueueFiller = new DBJob() {
		public void run(ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "Filling request queue... (SSK="+isSSKScheduler+" insert="+isInsertScheduler);
			ChosenRequest req = null;
			synchronized(starterQueue) {
				int size = starterQueue.size();
				if(logMINOR) Logger.minor(this, "Queue size: "+size+" SSK="+isSSKScheduler+" insert="+isInsertScheduler);
				if(size > MAX_STARTER_QUEUE_SIZE * 3 / 4) {
					return;
				}
				if(size >= MAX_STARTER_QUEUE_SIZE) {
					if(size >= WARNING_STARTER_QUEUE_SIZE)
						Logger.error(this, "Queue already full: "+starterQueue.size());
					return;
				}
			}
			SendableRequest lastReq = null;
			int sameKey = 0;
			while(true) {
				req = null;
				if(lastReq != null && sameKey < MAX_CONSECUTIVE_SAME_REQ &&
						lastReq.getParentGrabArray() != null) {
					req = schedCore.maybeMakeChosenRequest(lastReq, container, context);
					sameKey++;
				}
				if(req == null) {
					req = removeFirst(container, false, true);
					if(sameKey > 1)
						Logger.normal(this, "Selected "+sameKey+" requests from same SendableRequest: "+lastReq);
					sameKey = 0;
				}
				if(req == null) return;
				lastReq = req.request;
				if(logMINOR) Logger.minor(this, "Activating key");
				container.activate(req.key, 5);
				container.activate(req.ckey, 5);
				container.activate(req.request, 1);
				container.activate(req.request.getClientRequest(), 1);
				if(logMINOR) Logger.minor(this, "Activated");
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
	
	public void removePendingKey(final GotKeyListener getter, final boolean complain, final Key key, ObjectContainer container) {
		if(!getter.persistent()) {
			boolean dropped = schedTransient.removePendingKey(getter, complain, key, container);
			if(dropped && offeredKeys != null && !node.peersWantKey(key)) {
				for(int i=0;i<offeredKeys.length;i++)
					offeredKeys[i].remove(key);
			}
			if(transientCooldownQueue != null) {
				SendableGet cooldownGetter = getter.getRequest(key, container);
				if(cooldownGetter != null)
					transientCooldownQueue.removeKey(key, cooldownGetter, cooldownGetter.getCooldownWakeupByKey(key, null), null);
			}
		} else if(container != null) {
			// We are on the database thread already.
			schedCore.removePendingKey(getter, complain, key, container);
			if(persistentCooldownQueue != null) {
				SendableGet cooldownGetter = getter.getRequest(key, container);
				container.activate(cooldownGetter, 1);
				persistentCooldownQueue.removeKey(key, cooldownGetter, cooldownGetter.getCooldownWakeupByKey(key, container), container);
				container.deactivate(cooldownGetter, 1);
			}
		} else {
			jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					container.activate(getter, 1);
					schedCore.removePendingKey(getter, complain, key, container);
					if(persistentCooldownQueue != null) {
						SendableGet cooldownGetter = getter.getRequest(key, container);
						container.activate(cooldownGetter, 1);
						persistentCooldownQueue.removeKey(key, cooldownGetter, cooldownGetter.getCooldownWakeupByKey(key, container), container);
						container.deactivate(cooldownGetter, 1);
					}
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
	public void removePendingKeys(GotKeyListener getter, boolean complain) {
		ObjectContainer container;
		if(getter.persistent()) {
			container = selectorContainer;
			if(!databaseExecutor.onThread()) {
				throw new IllegalStateException("Not on database thread!");
			}
		} else {
			container = null;
		}
		Key[] keys = getter.listKeys(container);
		for(int i=0;i<keys.length;i++) {
			removePendingKey(getter, complain, keys[i], container);
		}
	}

	public void reregisterAll(final ClientRequester request, ObjectContainer container) {
		schedTransient.reregisterAll(request, random, this, container);
		schedCore.reregisterAll(request, random, this, container);
		starter.wakeUp();
	}
	
	public String getChoosenPriorityScheduler() {
		return choosenPriorityScheduler;
	}

	/*
	 * tripPendingKey() callbacks must run quickly, since we've found a block.
	 * succeeded() must run quickly, since we delete the PersistentChosenRequest.
	 * tripPendingKey() must run before succeeded() so we don't choose the same
	 * request again, then remove it from pendingKeys before it completes! 
	 */
	static final short TRIP_PENDING_PRIORITY = NativeThread.HIGH_PRIORITY-1;
	
	public synchronized void succeeded(final BaseSendableGet succeeded, final ChosenRequest req) {
		if(req.isPersistent()) {
			jobRunner.queue(new DBJob() {

				public void run(ObjectContainer container, ClientContext context) {
					schedCore.succeeded(succeeded, container);
					container.delete((PersistentChosenRequest)req);
				}
				
			}, TRIP_PENDING_PRIORITY, false);
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
		final GotKeyListener[] transientGets = schedTransient.removePendingKey(key, null);
		if(transientGets != null && transientGets.length > 0) {
			node.executor.execute(new Runnable() {
				public void run() {
					if(logMINOR) Logger.minor(this, "Running "+transientGets.length+" callbacks off-thread for "+block.getKey());
					for(int i=0;i<transientGets.length;i++) {
						try {
							if(logMINOR) Logger.minor(this, "Calling tripPendingKey() callback for "+transientGets[i]+" for "+key);
							transientGets[i].onGotKey(key, block, null, clientContext);
						} catch (Throwable t) {
							Logger.error(this, "Caught "+t+" running tripPendingKey() callback "+transientGets[i]+" for "+key, t);
						}
					}
				}
			}, "Running off-thread callbacks for "+block.getKey());
			if(transientCooldownQueue != null) {
				for(int i=0;i<transientGets.length;i++) {
					GotKeyListener got = transientGets[i];
					SendableGet req = got.getRequest(key, null);
					if(req == null) continue;
					transientCooldownQueue.removeKey(key, req, req.getCooldownWakeupByKey(key, null), null);
				}
			}
		}
		
		// Now the persistent stuff
		
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				container.activate(key, 1);
				if(logMINOR) Logger.minor(this, "tripPendingKey for "+key);
				final GotKeyListener[] gets = schedCore.removePendingKey(key, container);
				if(gets == null) return;
				if(persistentCooldownQueue != null) {
					for(int i=0;i<gets.length;i++) {
						GotKeyListener got = gets[i];
						container.activate(got, 1);
						SendableGet req = got.getRequest(key, container);
						container.activate(req, 1);
						if(req == null) continue;
						persistentCooldownQueue.removeKey(key, req, req.getCooldownWakeupByKey(key, container), container);
					}
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
			
		}, TRIP_PENDING_PRIORITY, false);
		
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

	private final DBJob moveFromCooldownJob = new DBJob() {
		
		public void run(ObjectContainer container, ClientContext context) {
			if(moveKeysFromCooldownQueue(persistentCooldownQueue, true, selectorContainer) ||
					moveKeysFromCooldownQueue(transientCooldownQueue, true, selectorContainer))
				starter.wakeUp();
		}
		
	};
	
	public void moveKeysFromCooldownQueue() {
		jobRunner.queue(moveFromCooldownJob, NativeThread.NORM_PRIORITY, true);
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
			GotKeyListener[] gets = schedCore.getClientsForPendingKey(key, container);
			GotKeyListener[] transientGets = schedTransient.getClientsForPendingKey(key, null);
			if(gets == null && transientGets == null) {
				// Not an error as this can happen due to race conditions etc.
				if(logMINOR) Logger.minor(this, "Restoring key but no keys queued?? for "+key);
				continue;
			} else {
				if(gets != null)
				for(int i=0;i<gets.length;i++) {
					if(persistent)
						container.activate(gets[i], 1);
					GotKeyListener got = gets[i];
					SendableGet req = got.getRequest(key, container);
					if(req == null) {
						Logger.error(this, "No request for listener "+got+" while requeueing "+key);
					}
					req.requeueAfterCooldown(key, now, container, clientContext);
				}
				if(transientGets != null)
				for(int i=0;i<transientGets.length;i++) {
					GotKeyListener got = transientGets[i];
					SendableGet req = got.getRequest(key, null);
					if(req == null) {
						Logger.error(this, "No request for listener "+got+" while requeueing "+key);
					}
					req.requeueAfterCooldown(key, now, container, clientContext);
				}
			}
		}
		return found;
	}

	public long countTransientQueuedRequests() {
		return schedTransient.countQueuedRequests(null);
	}

	public KeysFetchingLocally fetchingKeys() {
		return schedCore;
	}

	public void removeFetchingKey(Key key) {
		schedCore.removeFetchingKey(key);
	}
	
	public void removeChosenRequest(ChosenRequest req) {
		schedCore.removeChosenRequest(req);
	}

	public void callFailure(final SendableGet get, final LowLevelGetException e, final Object keyNum, int prio, final ChosenRequest req, boolean persistent) {
		if(!persistent) {
			get.onFailure(e, keyNum, null, clientContext);
			return;
		}
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				container.activate(get, 1);
				if(logMINOR)
					Logger.minor(this, "callFailure() on "+get+" : "+e);
				get.onFailure(e, keyNum, selectorContainer, clientContext);
				if(logMINOR)
					Logger.minor(this, "Deleting "+req);
				selectorContainer.delete((PersistentChosenRequest)req);
			}
			
		}, prio, false);
	}
	
	public void callFailure(final SendableInsert put, final LowLevelPutException e, final Object keyNum, int prio, final ChosenRequest req, boolean persistent) {
		if(!persistent) {
			put.onFailure(e, keyNum, null, clientContext);
			return;
		}
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				container.activate(put, 1);
				put.onFailure(e, keyNum, selectorContainer, clientContext);
				if(logMINOR)
					Logger.minor(this, "Deleting "+req);
				selectorContainer.delete((PersistentChosenRequest)req);
			}
			
		}, NativeThread.NORM_PRIORITY, false);
	}

	public void callSuccess(final SendableInsert put, final Object keyNum, int prio, final ChosenRequest req, boolean persistent) {
		if(!persistent) {
			put.onSuccess(keyNum, null, clientContext);
			return;
		}
		jobRunner.queue(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				container.activate(put, 1);
				put.onSuccess(keyNum, selectorContainer, clientContext);
				if(logMINOR)
					Logger.minor(this, "Deleting "+req);
				selectorContainer.delete((PersistentChosenRequest)req);
			}
			
		}, NativeThread.NORM_PRIORITY+1, false);
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
					if(logMINOR)
						Logger.minor(this, "Requeueing "+req);
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
	
	public long countPersistentQueuedRequests(ObjectContainer container) {
		return schedCore.countQueuedRequests(container);
	}
	
	
}
