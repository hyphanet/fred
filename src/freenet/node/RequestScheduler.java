/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.FECQueue;
import freenet.client.async.ChosenBlock;
import freenet.client.async.ClientContext;
import freenet.keys.ClientKey;
import freenet.keys.Key;

public interface RequestScheduler {

	/**
	 * Tell the scheduler that a request from a specific RandomGrabArray succeeded.
	 * Definition of "succeeded" will vary, but the point is most schedulers will run another
	 * request from the parentGrabArray in the near future on the theory that if one works,
	 * another may also work. 
	 * @param req The request we ran, which must be deleted.
	 * */
	public void succeeded(BaseSendableGet get, boolean persistent);

	/**
	 * After a key has been requested a few times, it is added to the cooldown queue for
	 * a fixed period, since it would be pointless to rerequest it (especially given ULPRs).
	 * Note that while a key is on the cooldown queue its requestors will still be told if
	 * it is found by ULPRs or back door coalescing.
	 * @param key The key to be added.
	 * @return The time at which the key will leave the cooldown queue.
	 */
	long queueCooldown(ClientKey key, SendableGet getter, ObjectContainer container);

	/** Once a key has been requested a few times, don't request it again for 30 minutes. 
	 * To do so would be pointless given ULPRs, and just waste bandwidth. */
	public static final long COOLDOWN_PERIOD = 30*60*1000;
	/** The number of times a key can be requested before triggering the cooldown period. 
	 * Note: If you don't want your requests to be subject to cooldown (e.g. in fproxy), make 
	 * your max retry count less than this (and more than -1). */
	public static final int COOLDOWN_RETRIES = 3;
	public long countTransientQueuedRequests();

	public void queueFillRequestStarterQueue();

	public KeysFetchingLocally fetchingKeys();

	public void removeFetchingKey(Key key);
	
	public void callFailure(SendableGet get, LowLevelGetException e, int prio, boolean persistent);
	
	public void callFailure(SendableInsert insert, LowLevelPutException exception, int prio, boolean persistent);
	
	public FECQueue getFECQueue();

	public ClientContext getContext();
	
	public boolean addToFetching(Key key);

	public ChosenBlock grabRequest();

	public void removeRunningRequest(SendableRequest request, ObjectContainer container);

	/**
	 * This only works for persistent requests, because transient requests are not
	 * selected on a SendableRequest level, they are selected on a {SendableRequest, token} level.
	 */
	public abstract boolean isRunningOrQueuedPersistentRequest(SendableRequest request);
	
	/**
	 * Check whether a key is already being fetched. If it is, optionally remember who is
	 * asking so we can wake them up (on the cooldown queue) when the key fetch completes.
	 * @param key
	 * @param getterWaiting
	 * @param persistent
	 * @param container
	 * @return
	 */
	public boolean hasFetchingKey(Key key, BaseSendableGet getterWaiting, boolean persistent, ObjectContainer container);

	public void start(NodeClientCore core);

	public boolean addTransientInsertFetching(SendableInsert insert, Object token);

	public void removeTransientInsertFetching(SendableInsert insert, Object token);

	public void wakeStarter();

	/* FIXME SECURITY When/if introduce tunneling or similar mechanism for starting requests
	 * at a distance this will need to be reconsidered. See the comments on the caller in 
	 * RequestHandler (onAbort() handler). */
	public boolean wantKey(Key key);

}
