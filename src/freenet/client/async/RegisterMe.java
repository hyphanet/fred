package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.node.SendableGet;
import freenet.node.SendableRequest;

/**
 * These must be deleted once the request has been registered.
 * See PersistentChosenRequest.
 * @author toad
 */
public class RegisterMe {
	final GotKeyListener listener;
	final SendableGet[] getters;
	final SendableRequest nonGetRequest;
	final ClientRequestSchedulerCore core;
	final RegisterMeSortKey key;
	private final int hashCode;
	public final BlockSet blocks;
	
	RegisterMe(GotKeyListener listener, SendableGet[] getters, SendableRequest nonGetRequest, short prio, ClientRequestSchedulerCore core, BlockSet blocks) {
		this.listener = listener;
		this.getters = getters;
		this.core = core;
		this.nonGetRequest = nonGetRequest;
		this.key = new RegisterMeSortKey(prio);
		this.blocks = blocks;
		int hash = core.hashCode();
		if(listener != null)
			hash ^= listener.hashCode();
		if(getters != null) {
			for(int i=0;i<getters.length;i++)
				hash ^= getters[i].hashCode();
		}
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

