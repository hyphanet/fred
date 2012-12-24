/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.ArrayList;
import java.util.LinkedList;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.Db4oException;

import freenet.client.FECQueue;
import freenet.client.FetchException;
import freenet.client.async.ClientRequestSelector.SelectorReturn;
import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeSSK;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.LowLevelPutException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.PrioRunnable;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.support.Logger;
import freenet.support.PrioritizedSerialExecutor;
import freenet.support.TimeUtil;
import freenet.support.io.NativeThread;

/**
 * Every X seconds, the RequestSender calls the ClientRequestScheduler to
 * ask for a request to start. A request is then started, in its own 
 * thread. It is removed at that point.
 */
public class ClientRequestScheduler implements RequestScheduler {
	
	private ClientRequestSchedulerCore schedCore;
	final ClientRequestSchedulerNonPersistent schedTransient;
	private final transient ClientRequestSelector selector;
	
	private static volatile boolean logMINOR;
        private static volatile boolean logDEBUG;
	
	static {
		Logger.registerClass(ClientRequestScheduler.class);
	}
	
	/** Offered keys list. Only one, not split by priority, to prevent various attacks relating
	 * to offering specific keys and timing how long it takes for the node to request the key. 
	 * Non-persistent. */
	private final OfferedKeysList offeredKeys;
	// we have one for inserts and one for requests
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	final boolean isRTScheduler;
	final RandomSource random;
	private final RequestStarter starter;
	private final Node node;
	public final String name;
	private final CooldownQueue transientCooldownQueue;
	private CooldownQueue persistentCooldownQueue;
	final PrioritizedSerialExecutor databaseExecutor;
	final DatastoreChecker datastoreChecker;
	public final ClientContext clientContext;
	final DBJobRunner jobRunner;
	
	public static final String PRIORITY_NONE = "NONE";
	public static final String PRIORITY_SOFT = "SOFT";
	public static final String PRIORITY_HARD = "HARD";
	private String choosenPriorityScheduler; 
	
	public ClientRequestScheduler(boolean forInserts, boolean forSSKs, boolean forRT, RandomSource random, RequestStarter starter, Node node, NodeClientCore core, String name, ClientContext context) {
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		this.isRTScheduler = forRT;
		schedTransient = new ClientRequestSchedulerNonPersistent(this, forInserts, forSSKs, forRT, random);
		this.databaseExecutor = core.clientDatabaseExecutor;
		this.datastoreChecker = core.storeChecker;
		this.starter = starter;
		this.random = random;
		this.node = node;
		this.clientContext = context;
		selector = new ClientRequestSelector(forInserts, this);
		
		this.name = name;
		
		this.choosenPriorityScheduler = PRIORITY_HARD; // Will be reset later.
		if(!forInserts) {
			offeredKeys = new OfferedKeysList(core, random, (short)0, forSSKs, forRT);
		} else {
			offeredKeys = null;
		}
		if(!forInserts)
			transientCooldownQueue = new RequestCooldownQueue(COOLDOWN_PERIOD);
		else
			transientCooldownQueue = null;
		jobRunner = clientContext.jobRunner;
	}
	
	public void startCore(NodeClientCore core, long nodeDBHandle, ObjectContainer container) {
		schedCore = ClientRequestSchedulerCore.create(node, isInsertScheduler, isSSKScheduler, isRTScheduler, nodeDBHandle, container, COOLDOWN_PERIOD, core.clientDatabaseExecutor, this, clientContext);
		persistentCooldownQueue = schedCore.persistentCooldownQueue;
	}
	
	public static void loadKeyListeners(final ObjectContainer container, ClientContext context) {
		ObjectSet<HasKeyListener> results =
			Db4oBugs.query(container, HasKeyListener.class);
		while(true) {
			HasKeyListener l;
			try {
				if(!results.hasNext()) break;
				l = results.next();
			} catch (IllegalArgumentException e) {
				throw new Db4oException("Something is broken: "+e, e);
				// Allow caller to terminate database.
				// IllegalArgumentException isn't caught, but here it is exclusively caused by corrupt database and/or database bugs. :(
			}
			container.activate(l, 1);
			try {
				if(l.isCancelled(container)) continue;
				KeyListener listener = l.makeKeyListener(container, context, true);
				if(listener != null) {
					if(listener.isSSK())
						context.getSskFetchScheduler(listener.isRealTime()).addPersistentPendingKeys(listener);
					else
						context.getChkFetchScheduler(listener.isRealTime()).addPersistentPendingKeys(listener);
					if(logMINOR) Logger.minor(ClientRequestScheduler.class, "Loaded request key listener: "+listener+" for "+l);
				}
			} catch (KeyListenerConstructionException e) {
				System.err.println("FAILED TO LOAD REQUEST BLOOM FILTERS:");
				e.printStackTrace();
				Logger.error(ClientRequestSchedulerCore.class, "FAILED TO LOAD REQUEST BLOOM FILTERS: "+e, e);
			} catch (Throwable t) {
				// Probably an error on last startup???
				Logger.error(ClientRequestSchedulerCore.class, "FAILED TO LOAD REQUEST: "+t, t);
				System.err.println("FAILED TO LOAD REQUEST: "+t);
				t.printStackTrace();
			}
			container.deactivate(l, 1);
		}
	}

	@Override
	public void start(NodeClientCore core) {
		if(schedCore != null)
			schedCore.start(core);
		queueFillRequestStarterQueue();
	}
	
	/** Called by the  config. Callback
	 * 
	 * @param val
	 */
	public synchronized void setPriorityScheduler(String val){
		choosenPriorityScheduler = val;
	}
	
	static final int QUEUE_THRESHOLD = 100;
	
	public void registerInsert(final SendableRequest req, boolean persistent, boolean regmeOnly, ObjectContainer container) {
		if(!isInsertScheduler)
			throw new IllegalArgumentException("Adding a SendableInsert to a request scheduler!!");
		if(persistent) {
				if(regmeOnly) {
					long bootID = 0;
					boolean queueFull = jobRunner.getQueueSize(NativeThread.NORM_PRIORITY) >= QUEUE_THRESHOLD;
					if(!queueFull)
						bootID = this.node.bootID;
					final RegisterMe regme = new RegisterMe(req, req.getPriorityClass(container), schedCore, null, bootID);
					container.store(regme);
					if(logMINOR)
						Logger.minor(this, "Added insert RegisterMe: "+regme);
					if(!queueFull) {
					try {
						jobRunner.queue(new DBJob() {
							
							@Override
							public boolean run(ObjectContainer container, ClientContext context) {
								container.delete(regme);
								if(req.isCancelled(container)) {
									if(logMINOR) Logger.minor(this, "Request already cancelled");
									return false;
								}
								if(container.ext().isActive(req))
									Logger.error(this, "ALREADY ACTIVE: "+req+" in delayed insert register");
								container.activate(req, 1);
								registerInsert(req, true, false, container);
								container.deactivate(req, 1);
								return true;
							}
							
                                                        @Override
							public String toString() {
								return "registerInsert";
							}
							
						}, NativeThread.NORM_PRIORITY, false);
					} catch (DatabaseDisabledException e) {
						// Impossible, we are already on the database thread.
					}
					} else {
						schedCore.rerunRegisterMeRunner(jobRunner);
					}
					container.deactivate(req, 1);
					return;
				}
				schedCore.innerRegister(req, random, container, clientContext, null);
				starter.wakeUp();
		} else {
			schedTransient.innerRegister(req, random, null, clientContext, null);
			starter.wakeUp();
		}
	}
	
	/**
	 * Register a group of requests (not inserts): a GotKeyListener and/or one 
	 * or more SendableGet's.
	 * @param listener Listens for specific keys. Can be null if the listener
	 * is already registered i.e. on retrying.
	 * @param getters The actual requests to register to the request sender queue.
	 * @param persistent True if the request is persistent.
	 * @param onDatabaseThread True if we are running on the database thread.
	 * NOTE: delayedStoreCheck/probablyNotInStore is unnecessary because we only
	 * register the listener once.
	 * @throws FetchException 
	 */
	public void register(final HasKeyListener hasListener, final SendableGet[] getters, final boolean persistent, ObjectContainer container, final BlockSet blocks, final boolean noCheckStore) throws KeyListenerConstructionException {
		if(logMINOR)
			Logger.minor(this, "register("+persistent+","+hasListener+","+getters);
		if(isInsertScheduler) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			throw e;
		}
		if(persistent) {
				innerRegister(hasListener, getters, blocks, noCheckStore, container);
		} else {
			final KeyListener listener;
			if(hasListener != null) {
				listener = hasListener.makeKeyListener(container, clientContext, false);
				if(listener != null)
					schedTransient.addPendingKeys(listener);
				else
					Logger.normal(this, "No KeyListener for "+hasListener);
			} else
				listener = null;
			if(getters != null && !noCheckStore) {
				for(SendableGet getter : getters)
					datastoreChecker.queueTransientRequest(getter, blocks);
			} else {
				boolean anyValid = false;
				for(int i=0;i<getters.length;i++) {
					if(!(getters[i].isCancelled(null) || getters[i].getCooldownTime(container, clientContext, System.currentTimeMillis()) != 0))
						anyValid = true;
				}
				finishRegister(getters, false, container, anyValid, null);
			}
		}
	}
	
	
	private void innerRegister(final HasKeyListener hasListener, final SendableGet[] getters, final BlockSet blocks, boolean noCheckStore, ObjectContainer container) throws KeyListenerConstructionException {
		final KeyListener listener;
		if(hasListener != null) {
			if(hasListener.isCancelled(container)) {
				if(logMINOR) Logger.minor(this, "Key listener is cancelled, not registering: "+hasListener);
				return;
			}
			container.store(hasListener);
			listener = hasListener.makeKeyListener(container, clientContext, false);
			schedCore.addPendingKeys(listener);
		} else
			listener = null;
		
		// Avoid NPEs due to deactivation.
		if(getters != null) {
			for(SendableGet getter : getters) {
				container.activate(getter, 1);
				container.store(getter);
			}
		}
		
		if(isInsertScheduler)
			throw new IllegalStateException("finishRegister on an insert scheduler");
		if(!noCheckStore) {
			// Check the datastore before proceding.
			for(SendableGet getter : getters) {
				container.activate(getter, 1);
				datastoreChecker.queuePersistentRequest(getter, blocks, container, clientContext);
				container.deactivate(getter, 1);
			}
			
		} else {
			// We have already checked the datastore, this is a retry, the listener hasn't been unregistered.
			this.finishRegister(getters, true, container, true, null);
		}
	}

	void finishRegister(final SendableGet[] getters, boolean persistent, ObjectContainer container, final boolean anyValid, final DatastoreCheckerItem reg) {
		if(logMINOR) Logger.minor(this, "finishRegister for "+getters+" anyValid="+anyValid+" reg="+reg+" persistent="+persistent);
		if(isInsertScheduler) {
			IllegalStateException e = new IllegalStateException("finishRegister on an insert scheduler");
			for(int i=0;i<getters.length;i++) {
				if(persistent)
					container.activate(getters[i], 1);
				getters[i].internalError(e, this, container, clientContext, persistent);
				if(persistent)
					container.deactivate(getters[i], 1);
			}
			throw e;
		}
		if(persistent) {
			// Add to the persistent registration queue
				if(!databaseExecutor.onThread()) {
					throw new IllegalStateException("Not on database thread!");
				}
				if(persistent)
					container.activate(getters, 1);
				if(logMINOR)
					Logger.minor(this, "finishRegister() for "+getters);
				if(anyValid) {
					boolean wereAnyValid = false;
					for(int i=0;i<getters.length;i++) {
						SendableGet getter = getters[i];
						container.activate(getter, 1);
						// Just check isCancelled, we have already checked the cooldown.
						if(!(getter.isCancelled(container))) {
							wereAnyValid = true;
							if(!getter.preRegister(container, clientContext, true)) {
								schedCore.innerRegister(getter, random, container, clientContext, getters);
							}
						} else
							getter.preRegister(container, clientContext, false);

					}
					if(!wereAnyValid) {
						Logger.normal(this, "No requests valid: "+getters);
					}
				} else {
					Logger.normal(this, "No valid requests passed in: "+getters);
				}
				if(reg != null)
					container.delete(reg);
				queueFillRequestStarterQueue(true);
		} else {
			// Register immediately.
			for(int i=0;i<getters.length;i++) {
				
				if((!anyValid) || getters[i].isCancelled(null)) {
					getters[i].preRegister(container, clientContext, false);
					continue;
				} else {
					if(getters[i].preRegister(container, clientContext, true)) continue;
				}
				if(!getters[i].isCancelled(null))
					schedTransient.innerRegister(getters[i], random, null, clientContext, getters);
			}
			starter.wakeUp();
		}
	}

	/**
	 * Return a better non-persistent request, if one exists. If the best request
	 * is at the same priority as the priority passed in, 50% chance of accepting
	 * it.
	 * @param prio The priority of the persistent request we want to beat.
	 */
	public ChosenBlock getBetterNonPersistentRequest(short prio) {
		// removeFirstTransient() will return anything of the priority given or better.
		// We want to be fair on persistent vs transient, so we give it a 50% chance of wanting it to be *better* than the current priority, and a 50% chance of wanting it to be *at least as good as* the current priority.
		prio -= node.fastWeakRandom.nextBoolean() ? 1 : 0;
		if(prio < 0) return null;
		short fuzz = -1;
		if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
			fuzz = -1;
		else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
			fuzz = 0;
		return selector.removeFirstTransient(fuzz, random, offeredKeys, starter, schedTransient, prio, isRTScheduler, clientContext, null);
	}
	
	/**
	 * All the persistent SendableRequest's currently running (either actually in flight, just chosen,
	 * awaiting the callbacks being executed etc). Note that this is an ArrayList because we *must*
	 * compare by pointer: these objects may well implement hashCode() etc for use by other code, but 
	 * if they are deactivated, they will be unreliable. Fortunately, this will be fairly small most
	 * of the time, since a single SendableRequest might include 256 actual requests.
	 * 
	 * SYNCHRONIZATION: Synched on starterQueue.
	 */
	private final transient ArrayList<SendableRequest> runningPersistentRequests = new ArrayList<SendableRequest> ();
	
	@Override
	public void removeRunningRequest(SendableRequest request, ObjectContainer container) {
		synchronized(starterQueue) {
			for(int i=0;i<runningPersistentRequests.size();i++) {
				if(runningPersistentRequests.get(i) == request) {
					runningPersistentRequests.remove(i);
					i--;
					if(logMINOR)
						Logger.minor(this, "Removed running request "+request+" size now "+runningPersistentRequests.size());
				}
			}
		}
		// We *DO* need to call clearCooldown here because it only becomes runnable for persistent requests after it has been removed from starterQueue.
		boolean active = container.ext().isActive(request);
		if(!active) container.activate(request, 1);
		request.clearCooldown(container, clientContext, false);
		if(!active) container.deactivate(request, 1);
	}
	
	@Override
	public boolean isRunningOrQueuedPersistentRequest(SendableRequest request) {
		synchronized(starterQueue) {
			for(int i=0;i<runningPersistentRequests.size();i++) {
				if(runningPersistentRequests.get(i) == request)
					return true;
			}
			for(PersistentChosenRequest req : starterQueue) {
				if(req.request == request) return true;
			}
		}
		return false;
	}
	
	/** The maximum number of requests that we will keep on the in-RAM request
	 * starter queue. */
	static final int MAX_STARTER_QUEUE_SIZE = 512; // two full segments
	
	/** The above doesn't include in-flight requests. In-flight requests will
	 * of course still have PersistentChosenRequest's 
	 * even though they are not on the starter queue and so don't count towards
	 * the above limit. So we have a higher limit before we complain that 
	 * something odd is happening.. (e.g. leaking PersistentChosenRequest's). */
	static final int WARNING_STARTER_QUEUE_SIZE = 800;
	private static final long WAIT_AFTER_NOTHING_TO_START = 60*1000;
	
	private final transient LinkedList<PersistentChosenRequest> starterQueue = new LinkedList<PersistentChosenRequest>();
	
	/**
	 * Called by RequestStarter to find a request to run.
	 */
	@Override
	public ChosenBlock grabRequest() {
		boolean needsRefill = true;
		while(true) {
			PersistentChosenRequest reqGroup = null;
			synchronized(starterQueue) {
				short bestPriority = Short.MAX_VALUE;
				for(PersistentChosenRequest req : starterQueue) {
					if(req.prio == RequestStarter.MINIMUM_PRIORITY_CLASS) {
					    if(logDEBUG) Logger.debug(this, "Ignoring paused persistent request: "+req+" prio: "+req.prio);
					     continue; //Ignore paused requests
					}
					if(req.prio < bestPriority) {
						bestPriority = req.prio;
						reqGroup = req;
					}
				}
			}
			if(reqGroup != null) {
				// Try to find a better non-persistent request
				if(logMINOR) Logger.minor(this, "Persistent request: "+reqGroup+" prio "+reqGroup.prio);
				ChosenBlock better = getBetterNonPersistentRequest(reqGroup.prio);
				if(better != null) {
					if(better.getPriority() > reqGroup.prio) {
						Logger.error(this, "Selected "+better+" as better than "+reqGroup+" but isn't better!");
					}
					if(logMINOR) Logger.minor(this, "Returning better: "+better);
					return better;
				}
			}
			if(reqGroup == null) {
				queueFillRequestStarterQueue();
				return getBetterNonPersistentRequest(Short.MAX_VALUE);
			}
			ChosenBlock block;
			synchronized(starterQueue) {
				block = reqGroup.grabNotStarted(clientContext.fastWeakRandom, this);
				if(block == null) {
					if(logMINOR) Logger.minor(this, "No block found on "+reqGroup);
					int finalLength = 0;
					for(int i=0;i<starterQueue.size();i++) {
						if(starterQueue.get(i) == reqGroup) {
							starterQueue.remove(i);
							if(logMINOR)
								Logger.minor(this, "Removed "+reqGroup+" from starter queue because is empty");
							i--;
						} else {
							finalLength += starterQueue.get(i).sizeNotStarted();
						}
					}
					needsRefill = finalLength < MAX_STARTER_QUEUE_SIZE;
					continue;
				} else {
					// Prevent this request being selected, even though we may remove the PCR from the starter queue
					// in the very near future. When the PCR finishes, the requests will be un-blocked.
					if(!runningPersistentRequests.contains(reqGroup.request))
						runningPersistentRequests.add(reqGroup.request);
				}
			}
			if(needsRefill)
				queueFillRequestStarterQueue();
			if(logMINOR)
				Logger.minor(this, "grabRequest() returning "+block+" for "+reqGroup);
			return block;
		}
	}
	
	@Override
	public void queueFillRequestStarterQueue() {
		queueFillRequestStarterQueue(false);
	}
	
	/** Don't fill the starter queue until this point. Used to implement a 60 second
	 * cooldown after failing to fill the queue: if there was nothing queued, and since
	 * we know if more requests are started they will be added to the queue, this is
	 * an acceptable optimisation to reduce the database load from the idle schedulers... */
	private long nextQueueFillRequestStarterQueue = -1;
	
	public void queueFillRequestStarterQueue(boolean force) {
		if(System.currentTimeMillis() < nextQueueFillRequestStarterQueue && !force)
			return;
		if(starterQueueLength() > MAX_STARTER_QUEUE_SIZE / 2)
			return;
		try {
			jobRunner.queue(requestStarterQueueFiller, NativeThread.MAX_PRIORITY, true);
		} catch (DatabaseDisabledException e) {
			// Ok, do what we can
			moveKeysFromCooldownQueue(transientCooldownQueue, false, null);
		}
	}

	private int starterQueueLength() {
		int length = 0;
		synchronized(starterQueue) {
			for(PersistentChosenRequest request : starterQueue)
				length += request.sizeNotStarted();
		}
		return length;
	}

	/**
	 * @param request
	 * @param container
	 * @return True if the queue is now full/over-full.
	 */
	boolean addToStarterQueue(SendableRequest request, ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "Adding to starter queue: "+request);
		container.activate(request, 1);
		PersistentChosenRequest chosen;
		try {
			chosen = new PersistentChosenRequest(request, request.getPriorityClass(container), container, ClientRequestScheduler.this, clientContext);
		} catch (NoValidBlocksException e) {
			return false;
		}
		if(logMINOR)
			Logger.minor(this, "Created PCR: "+chosen);
		container.deactivate(request, 1);
		boolean dumpNew = false;
		synchronized(starterQueue) {
			for(PersistentChosenRequest req : starterQueue) {
				if(req.request == request) {
					Logger.error(this, "Already on starter queue: "+req+" for "+request, new Exception("debug"));
					dumpNew = true;
					break;
				}
			}
			if(!dumpNew) {
				starterQueue.add(chosen);
				int length = starterQueueLength();
				length += chosen.sizeNotStarted();
				runningPersistentRequests.add(request);
				if(logMINOR)
					Logger.minor(this, "Added to running persistent requests, size now "+runningPersistentRequests.size()+" : "+request);
				return length > MAX_STARTER_QUEUE_SIZE;
			}
		}
		if(dumpNew)
			chosen.onDumped(schedCore, container, false);
		return false;
	}
	
	void removeFromStarterQueue(SendableRequest req, ObjectContainer container, boolean reqAlreadyActive) {
		PersistentChosenRequest dumped = null;
		synchronized(starterQueue) {
			for(int i=0;i<starterQueue.size();i++) {
				PersistentChosenRequest pcr = starterQueue.get(i);
				if(pcr.request == req) {
					starterQueue.remove(i);
					dumped = pcr;
					break;
				}
			}
		}
		if(dumped != null)
			dumped.onDumped(schedCore, container, reqAlreadyActive);
	}
	
	int starterQueueSize() {
		synchronized(starterQueue) {
			return starterQueue.size();
		}
	}
	
	private DBJob requestStarterQueueFiller = new DBJob() {
		@Override
		public boolean run(ObjectContainer container, ClientContext context) {
			fillRequestStarterQueue(container, context);
			return false;
		}
        @Override
		public String toString() {
			return "fillRequestStarterQueue";
		}
	};
	
	private boolean fillingRequestStarterQueue;
	
	private void fillRequestStarterQueue(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(fillingRequestStarterQueue) return;
			fillingRequestStarterQueue = true;
		}
		long now = System.currentTimeMillis();
		try {
		if(logMINOR) Logger.minor(this, "Filling request queue... (SSK="+isSSKScheduler+" insert="+isInsertScheduler);
		long noLaterThan = Long.MAX_VALUE;
		boolean checkCooldownQueue = now > nextQueueFillRequestStarterQueue;
		if((!isInsertScheduler) && checkCooldownQueue) {
			if(persistentCooldownQueue != null)
				noLaterThan = moveKeysFromCooldownQueue(persistentCooldownQueue, true, container);
			noLaterThan = Math.min(noLaterThan, moveKeysFromCooldownQueue(transientCooldownQueue, false, container));
		}
		// If anything has been re-added, the request starter will have been woken up.
		short fuzz = -1;
		if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
			fuzz = -1;
		else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
			fuzz = 0;
		boolean added = false;
		synchronized(starterQueue) {
			if(logMINOR && (!isSSKScheduler) && (!isInsertScheduler)) {
				Logger.minor(this, "Scheduling CHK fetches...");
				for(SendableRequest req : runningPersistentRequests) {
					boolean wasActive = container.ext().isActive(req);
					if(!wasActive) container.activate(req, 1);
					Logger.minor(this, "Running persistent request: "+req);
					if(!wasActive) container.deactivate(req, 1);
				}
			}
			// Recompute starterQueueLength
			int length = 0;
			PersistentChosenRequest old = null;
			for(PersistentChosenRequest req : starterQueue) {
				if(old == req)
					Logger.error(this, "DUPLICATE CHOSEN REQUESTS ON QUEUE: "+req);
				if(old != null && old.request == req.request)
					Logger.error(this, "DUPLICATE REQUEST ON QUEUE: "+old+" vs "+req+" both "+req.request);
				boolean ignoreActive = false;
				if(!ignoreActive) {
					if(container.ext().isActive(req.request))
						Logger.warning(this, "REQUEST ALREADY ACTIVATED: "+req.request+" for "+req+" while checking request queue in filling request queue");
					else if(logMINOR)
						Logger.minor(this, "Not already activated for "+req+" in while checking request queue in filling request queue");
				} else if(logMINOR)
					Logger.minor(this, "Ignoring active because just registered: "+req.request);
				req.pruneDuplicates(ClientRequestScheduler.this);
				old = req;
				length += req.sizeNotStarted();
			}
			if(logMINOR) Logger.minor(this, "Queue size: "+length+" SSK="+isSSKScheduler+" insert="+isInsertScheduler);
			if(length > MAX_STARTER_QUEUE_SIZE * 3 / 4) {
				if(length >= WARNING_STARTER_QUEUE_SIZE)
					Logger.error(this, "Queue already full: "+length);
				return;
			}
		}
		
		if((!isSSKScheduler) && (!isInsertScheduler)) {
			Logger.minor(this, "Scheduling CHK fetches...");
		}
		boolean addedMore = false;
		while(true) {
			SelectorReturn r;
			// Must synchronize on scheduler to avoid problems with cooldown queue. See notes on CooldownTracker.clearCachedWakeup, which also applies to other cooldown operations.
			synchronized(this) {
				r = selector.removeFirstInner(fuzz, random, offeredKeys, starter, schedCore, schedTransient, false, true, Short.MAX_VALUE, isRTScheduler, context, container, now);
			}
			SendableRequest request = null;
			if(r != null && r.req != null) request = r.req;
			else {
				if(r != null && r.wakeupTime > 0 && noLaterThan > r.wakeupTime) {
					noLaterThan = r.wakeupTime;
					if(logMINOR) Logger.minor(this, "Waking up in "+TimeUtil.formatTime(noLaterThan - now)+" for cooldowns");
				}
			}
			if(request == null) {
				synchronized(ClientRequestScheduler.this) {
					// Don't wake up for a while, but no later than the time we expect the next item to come off the cooldown queue
					if(checkCooldownQueue && !added) {
						nextQueueFillRequestStarterQueue = 
							System.currentTimeMillis() + WAIT_AFTER_NOTHING_TO_START;
						if(nextQueueFillRequestStarterQueue > noLaterThan)
							nextQueueFillRequestStarterQueue = noLaterThan + 1;
					}
				}
				if(addedMore) starter.wakeUp();
				return;
			}
			boolean full = addToStarterQueue(request, container);
			container.deactivate(request, 1);
			if(!added) starter.wakeUp();
			else addedMore = true;
			added = true;
			if(full) {
				if(addedMore) starter.wakeUp();
				return;
			}
		}
		} finally {
			synchronized(this) {
			fillingRequestStarterQueue = false;
			}
		}
	}
	
	/**
	 * Compare a recently registered SendableRequest to what is already on the
	 * starter queue. If it is better, kick out stuff from the queue until we
	 * are just over the limit.
	 * @param req
	 * @param container
	 */
	public void maybeAddToStarterQueue(SendableRequest req, ObjectContainer container, SendableRequest[] mightBeActive) {
		short prio = req.getPriorityClass(container);
		if(logMINOR)
			Logger.minor(this, "Maybe adding to starter queue: prio="+prio);
		synchronized(starterQueue) {
			boolean betterThanSome = false;
			int size = 0;
			PersistentChosenRequest prev = null;
			for(PersistentChosenRequest old : starterQueue) {
				if(old.request == req) {
					// Wait for a reselect. Otherwise we can starve other
					// requests. Note that this happens with persistent SBI's:
					// they are added at the new retry count before being
					// removed at the old retry count.
					if(logMINOR) Logger.minor(this, "Already on starter queue: "+old+" for "+req);
					return;
				}
				if(prev == old)
					Logger.error(this, "ON STARTER QUEUE TWICE: "+prev+" for "+prev.request);
				if(prev != null && prev.request == old.request)
					Logger.error(this, "REQUEST ON STARTER QUEUE TWICE: "+prev+" for "+prev.request+" vs "+old+" for "+old.request);
				boolean ignoreActive = false;
				if(mightBeActive != null) {
					for(SendableRequest tmp : mightBeActive)
						if(tmp == old.request) ignoreActive = true;
				}
				if(!ignoreActive) {
					if(container.ext().isActive(old.request))
						Logger.warning(this, "REQUEST ALREADY ACTIVATED: "+old.request+" for "+old+" while checking request queue in maybeAddToStarterQueue for "+req);
					else if(logDEBUG)
						Logger.debug(this, "Not already activated for "+old+" in while checking request queue in maybeAddToStarterQueue for "+req);
				} else if(logMINOR)
					Logger.minor(this, "Ignoring active because just registered: "+old.request+" in maybeAddToStarterQueue for "+req);
				size += old.sizeNotStarted();
				if(old.prio > prio)
					betterThanSome = true;
				if(old.request == req) return;
				prev = old;
			}
			if(size >= MAX_STARTER_QUEUE_SIZE && !betterThanSome) {
				if(logMINOR)
					Logger.minor(this, "Not adding to starter queue: over limit and req not better than any queued requests");
				return;
			}
		}
		addToStarterQueue(req, container);
		trimStarterQueue(container);
	}
	
	private void trimStarterQueue(ObjectContainer container) {
		ArrayList<PersistentChosenRequest> dumped = null;
		synchronized(starterQueue) {
			int length = starterQueueLength();
			while(length > MAX_STARTER_QUEUE_SIZE) {
				// Find the lowest priority/retry count request.
				// If we can dump it without going below the limit, then do so.
				// If we can't, return.
				PersistentChosenRequest worst = null;
				short worstPrio = -1;
				int worstIndex = -1;
				int worstLength = -1;
				if(starterQueue.isEmpty()) {
					break;
				}
				length = 0;
				for(int i=0;i<starterQueue.size();i++) {
					PersistentChosenRequest req = starterQueue.get(i);
					short prio = req.prio;
					int size = req.sizeNotStarted();
					length += size;
					if(prio > worstPrio) {
						worstPrio = prio;
						worst = req;
						worstIndex = i;
						worstLength = size;
						continue;
					}
				}
				int lengthAfter = length - worstLength;
				if(lengthAfter >= MAX_STARTER_QUEUE_SIZE) {
					if(dumped == null)
						dumped = new ArrayList<PersistentChosenRequest>(2);
					dumped.add(worst);
					starterQueue.remove(worstIndex);
					if(lengthAfter == MAX_STARTER_QUEUE_SIZE) break;
				} else {
					// Can't remove any more.
					break;
				}
			}
		}
		if(dumped == null) return;
		for(PersistentChosenRequest req : dumped) {
			req.onDumped(schedCore, container, false);
		}
	}

	/**
	 * Remove a KeyListener from the list of KeyListeners.
	 * @param getter
	 * @param complain
	 */
	public void removePendingKeys(KeyListener getter, boolean complain) {
		boolean found = schedTransient.removePendingKeys(getter);
		if(schedCore != null)
			found |= schedCore.removePendingKeys(getter);
		if(complain && !found)
			Logger.error(this, "Listener not found when removing: "+getter);
	}

	/**
	 * Remove a KeyListener from the list of KeyListeners.
	 * @param getter
	 * @param complain
	 */
	public void removePendingKeys(HasKeyListener getter, boolean complain) {
		boolean found = schedTransient.removePendingKeys(getter);
		if(schedCore != null)
			found |= schedCore.removePendingKeys(getter);
		if(complain && !found)
			Logger.error(this, "Listener not found when removing: "+getter);
	}

	public void reregisterAll(final ClientRequester request, ObjectContainer container, short oldPrio) {
		schedTransient.reregisterAll(request, random, this, null, clientContext, oldPrio);
		if(schedCore != null)
			schedCore.reregisterAll(request, random, this, container, clientContext, oldPrio);
		starter.wakeUp();
	}
	
	public String getChoosenPriorityScheduler() {
		return choosenPriorityScheduler;
	}

	static final int TRIP_PENDING_PRIORITY = NativeThread.HIGH_PRIORITY-1;
	
	@Override
	public synchronized void succeeded(final BaseSendableGet succeeded, boolean persistent) {
		if(persistent) {
			try {
				jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						if(container.ext().isActive(succeeded))
							Logger.error(this, "ALREADY ACTIVE in succeeded(): "+succeeded);
						container.activate(succeeded, 1);
						schedCore.succeeded(succeeded, container);
						container.deactivate(succeeded, 1);
						return false;
					}
                                        @Override
					public String toString() {
						return "BaseSendableGet succeeded";
					}
					
				}, TRIP_PENDING_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
				Logger.error(this, "succeeded() on a persistent request but database disabled", new Exception("error"));
			}
			// Boost the priority so the PersistentChosenRequest gets deleted reasonably quickly.
		} else
			schedTransient.succeeded(succeeded, null);
	}

	public void tripPendingKey(final KeyBlock block) {
		if(logMINOR) Logger.minor(this, "tripPendingKey("+block.getKey()+")");
		
		if(offeredKeys != null) {
			offeredKeys.remove(block.getKey());
		}
		final Key key = block.getKey();
		if(schedTransient.anyProbablyWantKey(key, clientContext)) {
			this.clientContext.mainExecutor.execute(new PrioRunnable() {

				@Override
				public void run() {
					schedTransient.tripPendingKey(key, block, null, clientContext);
				}

				@Override
				public int getPriority() {
					return TRIP_PENDING_PRIORITY;
				}
				
			}, "Trip pending key (transient)");
		}
		if(schedCore == null) return;
		if(schedCore.anyProbablyWantKey(key, clientContext)) {
			try {
				jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						if(logMINOR) Logger.minor(this, "tripPendingKey for "+key);
						if(schedCore.tripPendingKey(key, block, container, clientContext))
							context.jobRunner.setCommitSoon();
						return false;
					}
					
					@Override
					public String toString() {
						return "tripPendingKey";
					}
				}, TRIP_PENDING_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
				// Nothing to do
			}
		} else schedCore.countNegative();
	}
	
	/* FIXME SECURITY When/if introduce tunneling or similar mechanism for starting requests
	 * at a distance this will need to be reconsidered. See the comments on the caller in 
	 * RequestHandler (onAbort() handler). */
	@Override
	public boolean wantKey(Key key) {
		if(schedTransient.anyProbablyWantKey(key, clientContext)) return true;
		if(schedCore != null && schedCore.anyProbablyWantKey(key, clientContext)) return true;
		return false;
	}

	/** Queue the offered key */
	public void queueOfferedKey(final Key key, boolean realTime) {
		if(logMINOR)
			Logger.minor(this, "queueOfferedKey("+key);
		offeredKeys.queueKey(key);
		starter.wakeUp();
	}

	public void dequeueOfferedKey(Key key) {
		offeredKeys.remove(key);
	}

	/**
	 * Restore keys from the given cooldown queue. Find any keys that are due to be
	 * restored, restore all requests both persistent and non-persistent for those keys.
	 * @param queue
	 * @param persistent
	 * @param container
	 * @return Long.MAX_VALUE if nothing is queued in the next WAIT_AFTER_NOTHING_TO_START
	 * millis, the time at which the next key is due to be restored if there are keys queued
	 * to be restarted in the near future.
	 */
	private long moveKeysFromCooldownQueue(CooldownQueue queue, boolean persistent, ObjectContainer container) {
		if(queue == null) return Long.MAX_VALUE;
		long now = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Moving keys from cooldown queue persistent="+persistent);
		/*
		 * Only go around once. We will be called again. If there are keys to move, then RequestStarter will not
		 * sleep, because it will start them. Then it will come back here. If we are off-thread i.e. on the database
		 * thread, then we will wake it up if we find keys... and we'll be scheduled again.
		 * 
		 * FIXME: I think we need to restore all the listeners for a single key 
		 * simultaneously to avoid some kind of race condition? Or could we just
		 * restore the one request on the queue? Maybe it's just a misguided
		 * optimisation? IIRC we had some severe problems when we didn't have 
		 * this, related to requests somehow being lost altogether... Is it 
		 * essential? We can save a query if it's not... Is this about requests
		 * or about keys? Should we limit all requests across any 
		 * SendableRequest's to 3 every half hour for a specific key? Probably 
		 * yes...? In which case, can the cooldown queue be entirely in RAM,
		 * and would it be useful for it to be? Less disk, more RAM... for fast
		 * nodes with little RAM it would be bad...
		 */
		final int MAX_KEYS = 20;
		Object ret;
		ClientRequestScheduler otherScheduler = 
			((!isSSKScheduler) ? this.clientContext.getSskFetchScheduler(isRTScheduler) : this.clientContext.getChkFetchScheduler(isRTScheduler));
		if(queue instanceof PersistentCooldownQueue) {
			ret = ((PersistentCooldownQueue)queue).removeKeyBefore(now, WAIT_AFTER_NOTHING_TO_START, container, MAX_KEYS, (PersistentCooldownQueue)otherScheduler.persistentCooldownQueue);
		} else
			ret = queue.removeKeyBefore(now, WAIT_AFTER_NOTHING_TO_START, container, MAX_KEYS);
		if(ret == null) return Long.MAX_VALUE;
		if(ret instanceof Long) {
			return (Long) ret;
		}
		Key[] keys = (Key[]) ret;
		for(int j=0;j<keys.length;j++) {
			Key key = keys[j];
			if(persistent)
				container.activate(key, 5);
			if(logMINOR) Logger.minor(this, "Restoring key: "+key);
			if(key instanceof NodeSSK == isSSKScheduler)
				restoreKey(key, container, now);
			else
				otherScheduler.restoreKey(key, container, now); 
			if(persistent)
				container.deactivate(key, 5);
		}
		return Long.MAX_VALUE;
	}

	private void restoreKey(Key key, ObjectContainer container, long now) {
		SendableGet[] reqs = container == null ? null : (schedCore == null ? null : schedCore.requestsForKey(key, container, clientContext));
		SendableGet[] transientReqs = schedTransient.requestsForKey(key, container, clientContext);
		if(reqs == null && transientReqs == null) {
			// Not an error as this can happen due to race conditions etc.
			if(logMINOR) Logger.minor(this, "Restoring key but no keys queued?? for "+key);
		}
		if(reqs != null) {
			for(int i=0;i<reqs.length;i++) {
				// Requests may or may not be returned activated from requestsForKey(), so don't check
				// But do deactivate them once we're done with them.
				container.activate(reqs[i], 1);
				reqs[i].requeueAfterCooldown(key, now, container, clientContext);
				container.deactivate(reqs[i], 1);
			}
		}
		if(transientReqs != null) {
			for(int i=0;i<transientReqs.length;i++)
				transientReqs[i].requeueAfterCooldown(key, now, container, clientContext);
		}
	}

	@Override
	public long countTransientQueuedRequests() {
		return schedTransient.countQueuedRequests(null, clientContext);
	}

	@Override
	public KeysFetchingLocally fetchingKeys() {
		return selector;
	}

	@Override
	public void removeFetchingKey(Key key) {
		// Don't need to call clearCooldown(), because selector will do it for each request blocked on the key.
		selector.removeFetchingKey(key);
	}

	@Override
	public void removeTransientInsertFetching(SendableInsert insert, Object token) {
		selector.removeTransientInsertFetching(insert, token);
		// Must remove here, because blocks selection and therefore creates cooldown cache entries.
		insert.clearCooldown(null, clientContext, false);
	}
	
	@Override
	public void callFailure(final SendableGet get, final LowLevelGetException e, int prio, boolean persistent) {
		if(!persistent) {
			get.onFailure(e, null, null, clientContext);
		} else {
			try {
				jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						if(container.ext().isActive(get))
							Logger.error(this, "ALREADY ACTIVE: "+get+" in callFailure(request)");
						container.activate(get, 1);
						get.onFailure(e, null, container, clientContext);
						container.deactivate(get, 1);
						return false;
					}
                                        @Override
					public String toString() {
						return "SendableGet onFailure";
					}
					
				}, prio, false);
			} catch (DatabaseDisabledException e1) {
				Logger.error(this, "callFailure() on a persistent request but database disabled", new Exception("error"));
			}
		}
	}
	
	@Override
	public void callFailure(final SendableInsert insert, final LowLevelPutException e, int prio, boolean persistent) {
		if(!persistent) {
			insert.onFailure(e, null, null, clientContext);
		} else {
			try {
				jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						if(container.ext().isActive(insert))
							Logger.error(this, "ALREADY ACTIVE: "+insert+" in callFailure(insert)");
						container.activate(insert, 1);
						insert.onFailure(e, null, container, context);
						container.deactivate(insert, 1);
						return false;
					}
                                        @Override
					public String toString() {
						return "SendableInsert onFailure";
					}
					
				}, prio, false);
			} catch (DatabaseDisabledException e1) {
				Logger.error(this, "callFailure() on a persistent request but database disabled", new Exception("error"));
			}
		}
	}
	
	@Override
	public FECQueue getFECQueue() {
		return clientContext.fecQueue;
	}

	@Override
	public ClientContext getContext() {
		return clientContext;
	}

	/**
	 * @return True unless the key was already present.
	 */
	@Override
	public boolean addToFetching(Key key) {
		return selector.addToFetching(key);
	}
	
	@Override
	public boolean addTransientInsertFetching(SendableInsert insert, Object token) {
		return selector.addTransientInsertFetching(insert, token);
	}
	
	@Override
	public boolean hasFetchingKey(Key key, BaseSendableGet getterWaiting, boolean persistent, ObjectContainer container) {
		return selector.hasKey(key, null, false, null);
	}

	public long countPersistentWaitingKeys(ObjectContainer container) {
		if(schedCore == null) return 0;
		return schedCore.countWaitingKeys(container);
	}
	
	public long countPersistentQueuedRequests(ObjectContainer container) {
		if(schedCore == null) return 0;
		return schedCore.countQueuedRequests(container, clientContext);
	}

	public boolean isQueueAlmostEmpty() {
		return starterQueueSize() < MAX_STARTER_QUEUE_SIZE / 4;
	}
	
	public boolean isInsertScheduler() {
		return isInsertScheduler;
	}

	public void removeFromAllRequestsByClientRequest(ClientRequester clientRequest, SendableRequest get, boolean dontComplain, ObjectContainer container) {
		if(get.persistent())
			schedCore.removeFromAllRequestsByClientRequest(get, clientRequest, dontComplain, container);
		else
			schedTransient.removeFromAllRequestsByClientRequest(get, clientRequest, dontComplain, null);
	}

	void addPersistentPendingKeys(KeyListener listener) {
		schedCore.addPendingKeys(listener);
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing ClientRequestScheduler in database", new Exception("error"));
		return false;
	}

	@Override
	public void wakeStarter() {
		starter.wakeUp();
	}

	public byte[] saltKey(boolean persistent, Key key) {
		return persistent ? schedCore.saltKey(key) : schedTransient.saltKey(key);
	}

	/** Only used in rare special cases e.g. ClientRequestSelector.
	 * FIXME add some interfaces to get rid of this gross layer violation. */
	Node getNode() {
		return node;
	}

}
