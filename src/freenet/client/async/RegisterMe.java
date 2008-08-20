package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.node.SendableRequest;

/**
 * These must be deleted once the request has been registered.
 * See DatastoreCheckerItem: this class only handles inserts.
 * @author toad
 */
public class RegisterMe {
	final SendableRequest nonGetRequest;
	final ClientRequestSchedulerCore core;
	final RegisterMeSortKey key;
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
		this.key = new RegisterMeSortKey(prio);
		this.blocks = blocks;
		int hash = core.hashCode();
		if(nonGetRequest != null)
			hash ^= nonGetRequest.hashCode();
		hash *= prio;
		hashCode = hash;
	}
	
	public void objectOnActivate(ObjectContainer container) {
		container.activate(key, 1);
	}
	
	public int hashCode() {
		return hashCode;
	}
}

