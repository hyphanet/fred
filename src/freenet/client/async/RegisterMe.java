package freenet.client.async;

import freenet.node.SendableRequest;

/**
 * These must be deleted once the request has been registered.
 * See DatastoreCheckerItem: this class only handles inserts.
 * @author toad
 */
public class RegisterMe {
	final SendableRequest nonGetRequest;
	final ClientRequestSchedulerCore core;
	final long addedTime;
	final short priority;
	/**
	 * Only set if the key is on the queue.
	 */
	final long bootID;
	private final int hashCode;
	public final BlockSet blocks;
	
	RegisterMe(SendableRequest nonGetRequest, short prio, ClientRequestSchedulerCore core, BlockSet blocks, long bootID) {
		this.bootID = bootID;
		this.core = core;
		this.nonGetRequest = nonGetRequest;
		priority = prio;
		addedTime = System.currentTimeMillis();
		this.blocks = blocks;
		int hash = core.hashCode();
		hash ^= nonGetRequest.hashCode();
		hash *= prio;
		hashCode = hash;
	}
	
	public int hashCode() {
		return hashCode;
	}
}

