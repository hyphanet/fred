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
	private final RequestCooldownQueue cooldownQueue;
	
	public static final String PRIORITY_NONE = "NONE";
	public static final String PRIORITY_SOFT = "SOFT";
	public static final String PRIORITY_HARD = "HARD";
	private String choosenPriorityScheduler; 
	
	public ClientRequestScheduler(boolean forInserts, boolean forSSKs, RandomSource random, RequestStarter starter, Node node, NodeClientCore core, SubConfig sc, String name) {
		this.selectorContainer = node.dbServer.openClient();
		schedCore = ClientRequestSchedulerCore.create(node, forInserts, forSSKs, selectorContainer);
		schedTransient = new ClientRequestSchedulerNonPersistent(this);
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
			cooldownQueue = new RequestCooldownQueue(COOLDOWN_PERIOD);
		else
			cooldownQueue = null;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	/** Called by the  config. Callback
	 * 
	 * @param val
	 */
	protected synchronized void setPriorityScheduler(String val){
		choosenPriorityScheduler = val;
	}
	
	public void register(SendableRequest req) {
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
		if(req.persistent())
			schedCore.innerRegister(req, random);
		else
			schedTransient.innerRegister(req, random);
		starter.wakeUp();
	}

	void addPendingKey(ClientKey key, SendableGet getter) {
		if(getter.persistent())
			schedCore.addPendingKey(key, getter);
		else
			schedTransient.addPendingKey(key, getter);
	}
	
	public synchronized SendableRequest removeFirst() {
		short fuzz = -1;
		synchronized (this) {
			if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
				fuzz = -1;
			else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
				fuzz = 0;	
		}
		// schedCore juggles both
		return schedCore.removeFirst(fuzz, random, offeredKeys, starter, schedTransient);
	}
	
	public void removePendingKey(SendableGet getter, boolean complain, Key key) {
		boolean dropped = 
			schedCore.removePendingKey(getter, complain, key) ||
			schedTransient.removePendingKey(getter, complain, key);
		if(dropped && offeredKeys != null && !node.peersWantKey(key)) {
			for(int i=0;i<offeredKeys.length;i++)
				offeredKeys[i].remove(key);
		}
		if(cooldownQueue != null)
			cooldownQueue.removeKey(key, getter, getter.getCooldownWakeupByKey(key));
	}
	
	/**
	 * Remove a SendableGet from the list of getters we maintain for each key, indicating that we are no longer interested
	 * in that key.
	 * @param getter
	 * @param complain
	 */
	public void removePendingKeys(SendableGet getter, boolean complain) {
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

	public void reregisterAll(ClientRequester request) {
//		if(request.persistent())
			schedCore.reregisterAll(request, random);
//		else
//			schedTransient.reregisterAll(request, random);
//		starter.wakeUp();
	}
	
	public String getChoosenPriorityScheduler() {
		return choosenPriorityScheduler;
	}

	public void succeeded(BaseSendableGet succeeded) {
//		if(succeeded.persistent())
			schedCore.succeeded(succeeded);
//		else
//			schedTransient.succeeded(succeeded);
	}

	public void tripPendingKey(final KeyBlock block) {
		if(logMINOR) Logger.minor(this, "tripPendingKey("+block.getKey()+")");
		if(offeredKeys != null) {
			for(int i=0;i<offeredKeys.length;i++) {
				offeredKeys[i].remove(block.getKey());
			}
		}
		final Key key = block.getKey();
		final SendableGet[] gets = schedCore.removePendingKey(key);
		final SendableGet[] transientGets = schedTransient.removePendingKey(key);
		if(gets == null) return;
		if(cooldownQueue != null) {
			for(int i=0;i<gets.length;i++)
				cooldownQueue.removeKey(key, gets[i], gets[i].getCooldownWakeupByKey(key));
			for(int i=0;i<gets.length;i++)
				cooldownQueue.removeKey(key, transientGets[i], transientGets[i].getCooldownWakeupByKey(key));
		}

		Runnable r = new Runnable() {
			public void run() {
				if(logMINOR) Logger.minor(this, "Running "+gets.length+" callbacks off-thread for "+block.getKey());
				for(int i=0;i<transientGets.length;i++) {
					try {
						if(logMINOR) Logger.minor(this, "Calling callback for "+transientGets[i]+" for "+key);
						transientGets[i].onGotKey(key, block, ClientRequestScheduler.this);
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t+" running callback "+transientGets[i]+" for "+key);
					}
				}
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
		};
		node.getTicker().queueTimedJob(r, 0); // FIXME ideally these would be completed on a single thread; when we have 1.5, use a dedicated non-parallel Executor
	}

	public boolean anyWantKey(Key key) {
		return schedCore.anyWantKey(key) || schedTransient.anyWantKey(key);
	}

	/** If we want the offered key, or if force is enabled, queue it */
	public void maybeQueueOfferedKey(Key key, boolean force) {
		if(logMINOR)
			Logger.minor(this, "maybeQueueOfferedKey("+key+","+force);
		short priority = Short.MAX_VALUE;
		if(force) {
			// FIXME what priority???
			priority = RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
		}
		priority = schedCore.getKeyPrio(key, priority);
		priority = schedTransient.getKeyPrio(key, priority);
		if(priority == Short.MAX_VALUE) return;
		if(logMINOR)
			Logger.minor(this, "Priority: "+priority);
		offeredKeys[priority].queueKey(key);
		starter.wakeUp();
	}

	public void dequeueOfferedKey(Key key) {
		for(int i=0;i<offeredKeys.length;i++) {
			offeredKeys[i].remove(key);
		}
	}

	public long queueCooldown(ClientKey key, SendableGet getter) {
		return cooldownQueue.add(key.getNodeKey(), getter);
	}

	public void moveKeysFromCooldownQueue() {
		if(cooldownQueue == null) return;
		long now = System.currentTimeMillis();
		Key key;
		while((key = cooldownQueue.removeKeyBefore(now)) != null) { 
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
	}

	public long countQueuedRequests() {
		// Approximately... there might be some overlap in the two pendingKeys's...
		return schedCore.countQueuedRequests() + schedTransient.countQueuedRequests();
	}
}
