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
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableRequestSender;
import freenet.node.NodeClientCore.SimpleRequestSenderCompletionListener;
import freenet.support.Logger;

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

	private final HashSet keys;
	private final Vector keysList; // O(1) remove random element the way we use it, see chooseKey().
	private static boolean logMINOR;
	private final RandomSource random;
	private final short priorityClass;
	private final NodeClientCore core;
	private final boolean isSSK;
	
	OfferedKeysList(NodeClientCore core, RandomSource random, short priorityClass, boolean isSSK) {
		super(false);
		this.keys = new HashSet();
		this.keysList = new Vector();
		this.random = random;
		this.priorityClass = priorityClass;
		this.core = core;
		this.isSSK = isSSK;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	/** Called when a key is found, when it no longer belogns to this list etc. */
	public synchronized void remove(Key key) {
		assert(keysList.size() == keys.size());
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(keys.remove(key)) {
			keysList.remove(key);
			if(logMINOR) Logger.minor(this, "Found "+key+" , removing it "+" for "+this+" size now "+keysList.size());
		}
		assert(keysList.size() == keys.size());
	}
	
	public synchronized boolean isEmpty(ObjectContainer container) {
		return keys.isEmpty();
	}

	public Object[] allKeys(ObjectContainer container) {
		// Not supported.
		throw new UnsupportedOperationException();
	}

	public Object[] sendableKeys(ObjectContainer container) {
		// Not supported.
		throw new UnsupportedOperationException();
	}

	public synchronized Object chooseKey(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context) {
		assert(keysList.size() == keys.size());
		if(keys.size() == 1) {
			// Shortcut the common case
			Key k = (Key) keysList.get(0);
			if(fetching.hasKey(k)) return null;
			keys.remove(k);
			keysList.setSize(0);
			return k;
		}
		for(int i=0;i<10;i++) {
			// Pick a random key
			if(keysList.isEmpty()) return null;
			int ptr = random.nextInt(keysList.size());
			// Avoid shuffling penalty by swapping the chosen element with the end.
			Key k = (Key) keysList.get(ptr);
			if(fetching.hasKey(k)) continue;
			keysList.set(ptr, keysList.get(keysList.size()-1));
			keysList.setSize(keysList.size()-1);
			keys.remove(k);
			assert(keysList.size() == keys.size());
			return k;
		}
		return null;
	}

	public synchronized boolean hasValidKeys(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context) {
		assert(keysList.size() == keys.size());
		if(keys.size() == 1) {
			// Shortcut the common case
			Key k = (Key) keysList.get(0);
			if(fetching.hasKey(k)) return false;
			return true;
		}
		for(int i=0;i<10;i++) {
			// Pick a random key
			if(keysList.isEmpty()) return false;
			int ptr = random.nextInt(keysList.size());
			Key k = (Key) keysList.get(ptr);
			if(fetching.hasKey(k)) continue;
			return true;
		}
		return false;
	}

	public RequestClient getClient() {
		return this;
	}

	public ClientRequester getClientRequest() {
		// FIXME is this safe?
		return null;
	}

	public short getPriorityClass(ObjectContainer container) {
		return priorityClass;
	}

	public int getRetryCount() {
		return 0; // All keys have equal chance even if they've been tried before.
	}

	public void internalError(Throwable t, RequestScheduler sched, ObjectContainer container, ClientContext context, boolean persistent) {
		Logger.error(this, "Internal error: "+t, t);
	}
	
	@Override
	public SendableRequestSender getSender(ObjectContainer container, ClientContext context) {
		return new SendableRequestSender() {

			public boolean send(NodeClientCore core, RequestScheduler sched, ClientContext context, ChosenBlock req) {
				Key key = (Key) req.token;
				// Have to cache it in order to propagate it; FIXME
				// Don't let a node force us to start a real request for a specific key.
				// We check the datastore, take up offers if any (on a short timeout), and then quit if we still haven't fetched the data.
				// Obviously this may have a marginal impact on load but it should only be marginal.
				core.asyncGet(key, true, true, new SimpleRequestSenderCompletionListener() {

					public void completed(boolean success) {
						// Ignore
					}
					
				});
				return true;
			}
			
		};
	}

	public boolean canRemove(ObjectContainer container) {
		return false;
	}

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

	public Key getNodeKey(Object token, ObjectContainer container) {
		return (Key) token;
	}

	public boolean isSSK() {
		return isSSK;
	}

	@Override
	public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException("Transient only");
	}

}
