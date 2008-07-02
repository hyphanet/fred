package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.node.SendableRequest;

/**
 * These must be deleted once the request has been registered.
 * See PersistentChosenRequest.
 * @author toad
 */
public class RegisterMe {
	final SendableRequest getter;
	final ClientRequestSchedulerCore core;
	final RegisterMeSortKey key;
	private final int hashCode;
	
	RegisterMe(SendableRequest getter, short prio, ClientRequestSchedulerCore core) {
		hashCode = (getter.hashCode() * prio) ^ core.hashCode();
		this.getter = getter;
		this.core = core;
		this.key = new RegisterMeSortKey(prio);
	}
	
	public void objectOnActivate(ObjectContainer container) {
		container.activate(key, 1);
	}
	
	public int hashCode() {
		return hashCode;
	}
}

