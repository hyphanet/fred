/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashSet;
import java.util.List;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestCompletionListener;
import freenet.node.RequestScheduler;
import freenet.node.RequestSender;
import freenet.node.RequestSenderListener;
import freenet.node.SendableInsert;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestSender;
import freenet.node.NodeClientCore.SimpleRequestSenderCompletionListener;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * All the keys at a given priority which we have received key offers from other nodes for.
 * 
 * This list needs to be kept up to date when:
 * - A request is removed.
 * - A request's priority changes.
 * - A key is found.
 * - A node disconnects or restarts (through the BlockOffer objects on the FailureTable).
 * 
 * And of course, when an offer is received, we need to add an element.
 * 
 * @author toad
 *
 */
public class OfferedKeysList extends BaseSendableGet implements RequestClient {

	private final HashSet<Key> keys;
	private final Vector<Key> keysList; // O(1) remove random element the way we use it, see chooseKey().
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}
	private final RandomSource random;
	private final short priorityClass;
	private final boolean isSSK;
	
	OfferedKeysList(NodeClientCore core, RandomSource random, short priorityClass, boolean isSSK, boolean realTimeFlag) {
		super(false, realTimeFlag);
		this.keys = new HashSet<Key>();
		this.keysList = new Vector<Key>();
		this.random = random;
		this.priorityClass = priorityClass;
		this.isSSK = isSSK;
	}
	
	/** Called when a key is found, when it no longer belongs to this list etc. */
	public synchronized void remove(Key key) {
		assert(keysList.size() == keys.size());
		if(keys.remove(key)) {
			keysList.remove(key);
			if(logMINOR) Logger.minor(this, "Found "+key+" , removing it "+" for "+this+" size now "+keysList.size());
		}
		assert(keysList.size() == keys.size());
	}
	
	public synchronized boolean isEmpty(ObjectContainer container) {
		return keys.isEmpty();
	}

	@Override
	public long countAllKeys(ObjectContainer container, ClientContext context) {
		// Not supported.
		throw new UnsupportedOperationException();
	}

	@Override
	public long countSendableKeys(ObjectContainer container, ClientContext context) {
		// Not supported.
		throw new UnsupportedOperationException();
	}

	private static class MySendableRequestItem implements SendableRequestItem {
		final Key key;
		MySendableRequestItem(Key key) {
			this.key = key;
		}
		@Override
		public void dump() {
			// Ignore, we will be GC'ed
		}
	}
	
	@Override
	public synchronized SendableRequestItem chooseKey(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context) {
		assert(keysList.size() == keys.size());
		if(keys.size() == 1) {
			// Shortcut the common case
			Key k = keysList.get(0);
			if(fetching.hasKey(k, null, false, null)) return null;
			// Ignore RecentlyFailed because an offered key overrides it.
			keys.remove(k);
			keysList.setSize(0);
			return new MySendableRequestItem(k);
		}
		for(int i=0;i<10;i++) {
			// Pick a random key
			if(keysList.isEmpty()) return null;
			int ptr = random.nextInt(keysList.size());
			// Avoid shuffling penalty by swapping the chosen element with the end.
			Key k = keysList.get(ptr);
			if(fetching.hasKey(k, null, false, null)) continue;
			// Ignore RecentlyFailed because an offered key overrides it.
			keysList.set(ptr, keysList.get(keysList.size()-1));
			keysList.setSize(keysList.size()-1);
			keys.remove(k);
			assert(keysList.size() == keys.size());
			return new MySendableRequestItem(k);
		}
		return null;
	}

	@Override
	public RequestClient getClient(ObjectContainer container) {
		return this;
	}

	@Override
	public ClientRequester getClientRequest() {
		// FIXME is this safe?
		return null;
	}

	@Override
	public short getPriorityClass(ObjectContainer container) {
		return priorityClass;
	}

	@Override
	public void internalError(Throwable t, RequestScheduler sched, ObjectContainer container, ClientContext context, boolean persistent) {
		Logger.error(this, "Internal error: "+t, t);
	}
	
	@Override
	public SendableRequestSender getSender(ObjectContainer container, ClientContext context) {
		return new SendableRequestSender() {

			@Override
			public boolean send(NodeClientCore core, final RequestScheduler sched, ClientContext context, ChosenBlock req) {
				final Key key = ((MySendableRequestItem) req.token).key;
				// Have to cache it in order to propagate it; FIXME
				// Don't let a node force us to start a real request for a specific key.
				// We check the datastore, take up offers if any (on a short timeout), and then quit if we still haven't fetched the data.
				// Obviously this may have a marginal impact on load but it should only be marginal.
				core.asyncGet(key, true, new RequestCompletionListener() {

					@Override
					public void onSucceeded() {
						sched.removeFetchingKey(key);
						// Something might be waiting for a request to complete (e.g. if we have two requests for the same key), 
						// so wake the starter thread.
						sched.wakeStarter();
					}

					@Override
					public void onFailed(LowLevelGetException e) {
						sched.removeFetchingKey(key);
						// Something might be waiting for a request to complete (e.g. if we have two requests for the same key), 
						// so wake the starter thread.
						sched.wakeStarter();
					}

				}, true, false, realTimeFlag, false, false);
				// FIXME reconsider canWriteClientCache=false parameter.
				return true;
			}

			@Override
			public boolean sendIsBlocking() {
				return false;
			}
			
		};
	}

	@Override
	public boolean isCancelled(ObjectContainer container) {
		return false;
	}

	public synchronized void queueKey(Key key) {
		assert(keysList.size() == keys.size());
		if(keys.add(key)) {
			keysList.add(key);
			if(logMINOR) Logger.minor(this, "Queued key "+key+" on "+this);
		}
		assert(keysList.size() == keys.size());
	}

	@Override
	public Key getNodeKey(SendableRequestItem token, ObjectContainer container) {
		return ((MySendableRequestItem) token).key;
	}

	@Override
	public boolean isSSK() {
		return isSSK;
	}

	@Override
	public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException("Transient only");
	}

	@Override
	public boolean isInsert() {
		return false;
	}

	@Override
	public ClientRequestScheduler getScheduler(ObjectContainer container, ClientContext context) {
		if(isSSK)
			return context.getSskFetchScheduler(realTimeFlag);
		else
			return context.getChkFetchScheduler(realTimeFlag);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean preRegister(ObjectContainer container, ClientContext context, boolean toNetwork) {
		// Ignore
		return false;
	}

	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getCooldownTime(ObjectContainer container, ClientContext context, long now) {
		if(isEmpty(container)) return Long.MAX_VALUE;
		return 0;
	}

}
