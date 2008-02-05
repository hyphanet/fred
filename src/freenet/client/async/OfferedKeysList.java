/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashSet;
import java.util.Vector;

import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.node.NodeClientCore;
import freenet.node.RequestScheduler;
import freenet.node.SendableRequest;
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
public class OfferedKeysList extends SendableRequest {

	private final HashSet keys;
	// FIXME is there any way to avoid the O(n) shuffling penalty here?
	private final Vector keysList;
	private static boolean logMINOR;
	private final RandomSource random;
	private final short priorityClass;
	private final NodeClientCore core;
	
	OfferedKeysList(NodeClientCore core, RandomSource random, short priorityClass) {
		this.keys = new HashSet();
		this.keysList = new Vector();
		this.random = random;
		this.priorityClass = priorityClass;
		this.core = core;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	/** Called when a key is found, when it no longer belogns to this list etc. */
	public synchronized void remove(Key key) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(keys.remove(key)) {
			if(logMINOR) Logger.minor(this, "Found "+key+" , removing it");
			keysList.remove(key);
		}
	}
	
	public synchronized boolean isEmpty() {
		return keys.isEmpty();
	}

	public Object[] allKeys() {
		// Not supported.
		throw new UnsupportedOperationException();
	}

	public Object chooseKey() {
		// Pick a random key
		if(keysList.isEmpty()) return null;
		Key k = (Key) keysList.remove(random.nextInt(keysList.size()));
		keys.remove(k);
		return k;
	}

	public Object getClient() {
		return this;
	}

	public ClientRequester getClientRequest() {
		// FIXME is this safe?
		return null;
	}

	public short getPriorityClass() {
		return priorityClass;
	}

	public int getRetryCount() {
		return 0; // All keys have equal chance even if they've been tried before.
	}

	public void internalError(Object keyNum, Throwable t) {
		Logger.error(this, "Internal error: "+t, t);
	}
	
	public boolean send(NodeClientCore node, RequestScheduler sched, Object keyNum) {
		Key key = (Key) keyNum;
		core.asyncGet(key, true);// Have to cache it in order to propagate it; FIXME
		return true;
	}

	public boolean canRemove() {
		return false;
	}

	public boolean isCancelled() {
		return false;
	}

	public synchronized void queueKey(Key key) {
		if(keys.add(key)) {
			keysList.add(key);
			if(logMINOR) Logger.minor(this, "Queued key "+key);
		}
	}

}
