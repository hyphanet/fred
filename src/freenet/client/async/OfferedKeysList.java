/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Vector;

import freenet.crypt.RandomSource;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.LowLevelGetException;
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

	private final HashMap clientKeysByKey;
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
		clientKeysByKey = new HashMap();
		this.random = random;
		this.priorityClass = priorityClass;
		this.core = core;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	/** Called when a key is found */
	public synchronized void onFoundKey(Key key) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		ClientKey ck = (ClientKey) clientKeysByKey.remove(key);
		if(ck == null) return;
		if(logMINOR) Logger.minor(this, "Found "+key+" , removing it");
		keys.remove(key);
		keysList.remove(key);
	}
	
	/** Called when there are no more valid offers for a key */
	public synchronized void onNoOffers(ClientKey key) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "No offers for "+key+" , removing it");
		keys.remove(key);
		keysList.remove(key);
		clientKeysByKey.remove(key.getNodeKey());
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
		ClientKey ck = (ClientKey) keysList.remove(random.nextInt(keysList.size()));
		keys.remove(ck);
		clientKeysByKey.remove(ck.getNodeKey());
		return ck;
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
		ClientKey key = (ClientKey) keyNum;
		try {
			core.realGetKey(key, false, true, // if it's not cached it won't propagate FIXME support =false??
					false);
		} catch (LowLevelGetException e) {
			Logger.minor(this, "Caught low level get exception "+e, e);
		}
		return true;
	}

	public boolean canRemove() {
		return false;
	}

	public boolean isCancelled() {
		return false;
	}

}
