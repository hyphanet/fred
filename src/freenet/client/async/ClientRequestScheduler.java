/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

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
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.support.Logger;
import freenet.support.SerialExecutor;
import freenet.support.api.StringCallback;

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
	private final SerialExecutor databaseExecutor;
	
	public static final String PRIORITY_NONE = "NONE";
	public static final String PRIORITY_SOFT = "SOFT";
	public static final String PRIORITY_HARD = "HARD";
	private String choosenPriorityScheduler; 
	
	public ClientRequestScheduler(boolean forInserts, boolean forSSKs, RandomSource random, RequestStarter starter, Node node, NodeClientCore core, SubConfig sc, String name) {
		this.selectorContainer = node.db;
		schedCore = ClientRequestSchedulerCore.create(node, forInserts, forSSKs, selectorContainer, COOLDOWN_PERIOD);
		schedTransient = new ClientRequestSchedulerNonPersistent(this);
		persistentCooldownQueue = schedCore.persistentCooldownQueue;
		this.databaseExecutor = core.clientDatabaseExecutor;
		this.starter = starter;
		this.random = random;
		this.node = node;
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		
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
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Registering "+req, new Exception("debug"));
		if(isInsertScheduler != (req instanceof SendableInsert))
			throw new IllegalArgumentException("Expected a SendableInsert: "+req);
		if(req instanceof SendableGet) {
			SendableGet getter = (SendableGet)req;
			if(!getter.ignoreStore()) {
				boolean anyValid = false;
				Object[] keyTokens = getter.sendableKeys();
				for(int i=0;i<keyTokens.length;i++) {
					Object tok = keyTokens[i];
					ClientKeyBlock block = null;
					try {
						ClientKey key = getter.getKey(tok);
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
								addPendingKey(key, getter);
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
						getter.onFailure(new LowLevelGetException(LowLevelGetException.DECODE_FAILED), tok, this);
						continue; // other keys might be valid
					}
					if(block != null) {
						if(logMINOR) Logger.minor(this, "Can fulfill "+req+" ("+tok+") immediately from store");
						getter.onSuccess(block, true, tok, this);
						// Even with working thread priorities, we still get very high latency accessing
						// the datastore when background threads are doing it in parallel.
						// So yield() here, unless priority is very high.
						if(req.getPriorityClass() > RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS)
							Thread.yield();
					} else {
						anyValid = true;
					}
				}
				if(!anyValid) {
					if(logMINOR)
						Logger.minor(this, "No valid keys, returning without registering for "+req);
					return;
				}
			}
		}
		if(req.persistent()) {
			databaseExecutor.execute(new Runnable() {
				public void run() {
					try {
						schedCore.innerRegister(req, random);
						starter.wakeUp();
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t, t);
					}
				}
			}, "Register request");
		} else {
			schedTransient.innerRegister(req, random);
			starter.wakeUp();
		}
	}

	void addPendingKey(final ClientKey key, final SendableGet getter) {
		if(getter.persistent()) {
			databaseExecutor.execute(new Runnable() {
				public void run() {
					try {
						schedCore.addPendingKey(key, getter);
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t, t);
					}
				}
			}, "Add pending key");
		} else
			schedTransient.addPendingKey(key, getter);
	}
	
	public synchronized SendableRequest removeFirst() {
		short fuzz = -1;
		if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
			fuzz = -1;
		else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
			fuzz = 0;	
		// schedCore juggles both
		return schedCore.removeFirst(fuzz, random, offeredKeys, starter, schedTransient);
	}
	
	public void removePendingKey(final SendableGet getter, final boolean complain, final Key key) {
		if(getter.persistent()) {
			boolean dropped = schedTransient.removePendingKey(getter, complain, key);
			if(dropped && offeredKeys != null && !node.peersWantKey(key)) {
				for(int i=0;i<offeredKeys.length;i++)
					offeredKeys[i].remove(key);
			}
			if(transientCooldownQueue != null)
				transientCooldownQueue.removeKey(key, getter, getter.getCooldownWakeupByKey(key), null);
		} else {
			databaseExecutor.execute(new Runnable() {
				public void run() {
					try {
						schedCore.removePendingKey(getter, complain, key);
						if(persistentCooldownQueue != null)
							persistentCooldownQueue.removeKey(key, getter, getter.getCooldownWakeupByKey(key), selectorContainer);
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t, t);
					}
				}
				
			}, "removePendingKey");
		}
	}
	
	/**
	 * Remove a SendableGet from the list of getters we maintain for each key, indicating that we are no longer interested
	 * in that key.
	 * @param getter
	 * @param complain
	 */
	public void removePendingKeys(SendableGet getter, boolean complain) {
		// FIXME should this be a single databaseExecutor thread??
		Object[] keyTokens = getter.allKeys();
		for(int i=0;i<keyTokens.length;i++) {
			Object tok = keyTokens[i];
			ClientKey ckey = getter.getKey(tok);
			if(ckey == null) {
				if(complain)
					Logger.error(this, "Key "+tok+" is null for "+getter, new Exception("debug"));
				continue;
			}
			removePendingKey(getter, complain, ckey.getNodeKey());
		}
	}

	public void reregisterAll(final ClientRequester request) {
		if(request.persistent()) {
			databaseExecutor.execute(new Runnable() {
				public void run() {
					try {
						schedCore.reregisterAll(request, random, ClientRequestScheduler.this);
						starter.wakeUp();
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t, t);
					}
				}
			}, "Reregister for "+request);
		} else {
			schedTransient.reregisterAll(request, random, this);
			starter.wakeUp();
		}
	}
	
	public String getChoosenPriorityScheduler() {
		return choosenPriorityScheduler;
	}

	public synchronized void succeeded(final BaseSendableGet succeeded) {
		if(succeeded.persistent()) {
			databaseExecutor.execute(new Runnable() {
				public void run() {
					try {
						schedCore.succeeded(succeeded);
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t, t);
					}
				}
			}, "Mark success for "+succeeded);
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
		node.executor.execute(new Runnable() {
			public void run() {
				if(logMINOR) Logger.minor(this, "Running "+transientGets.length+" callbacks off-thread for "+block.getKey());
				for(int i=0;i<transientGets.length;i++) {
					try {
						if(logMINOR) Logger.minor(this, "Calling callback for "+transientGets[i]+" for "+key);
						transientGets[i].onGotKey(key, block, ClientRequestScheduler.this);
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t+" running callback "+transientGets[i]+" for "+key);
					}
				}
			}
		}, "Running off-thread callbacks for "+block.getKey());
		if(transientCooldownQueue != null) {
			for(int i=0;i<transientGets.length;i++)
				transientCooldownQueue.removeKey(key, transientGets[i], transientGets[i].getCooldownWakeupByKey(key), null);
		}
		
		// Now the persistent stuff
		
		databaseExecutor.execute(new Runnable() {

			public void run() {
				final SendableGet[] gets = schedCore.removePendingKey(key);
				if(gets == null) return;
				if(persistentCooldownQueue != null) {
					for(int i=0;i<gets.length;i++)
						persistentCooldownQueue.removeKey(key, gets[i], gets[i].getCooldownWakeupByKey(key), selectorContainer);
				}
				// Call the callbacks on the database executor thread, because the first thing
				// they will need to do is access the database to decide whether they need to
				// decode, and if so to find the key to decode with.
				for(int i=0;i<gets.length;i++) {
					try {
						if(logMINOR) Logger.minor(this, "Calling callback for "+gets[i]+" for "+key);
						gets[i].onGotKey(key, block, ClientRequestScheduler.this);
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t+" running callback "+gets[i]+" for "+key);
					}
				}
				if(logMINOR) Logger.minor(this, "Finished running callbacks");
			}
			
		}, "tripPendingKey for "+block.getKey());
		
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
		priority = schedCore.getKeyPrio(key, priority);
		if(priority < Short.MAX_VALUE) {
			offeredKeys[priority].queueKey(key);
			starter.wakeUp();
		}
		
		final short oldPrio = priority;
		
		databaseExecutor.execute(new Runnable() {
			public void run() {
				short priority = schedTransient.getKeyPrio(key, oldPrio);
				if(priority >= oldPrio) return; // already on list at >= priority
				offeredKeys[priority].queueKey(key);
				starter.wakeUp();
			}
		}, "maybeQueueOfferedKey");
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
		databaseExecutor.execute(new Runnable() {
			public void run() {
				try {
					if(moveKeysFromCooldownQueue(persistentCooldownQueue, selectorContainer))
						starter.wakeUp();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				}
			}
		}, "moveKeysFromCooldownQueue");
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
					gets[i].requeueAfterCooldown(key, now);
				if(transientGets != null)
				for(int i=0;i<transientGets.length;i++)
					transientGets[i].requeueAfterCooldown(key, now);
			}
		}
		if(keys.length < MAX_KEYS) return found;
		}
	}

	public long countTransientQueuedRequests() {
		// Approximately... there might be some overlap in the two pendingKeys's...
		return schedCore.countQueuedRequests() + schedTransient.countQueuedRequests();
	}
}
