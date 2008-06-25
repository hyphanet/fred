package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.node.SendableRequest;

public class RegisterMe {
	final SendableRequest getter;
	final ClientRequestSchedulerCore core;
	final RegisterMeSortKey key;
	
	RegisterMe(SendableRequest getter, ClientRequestSchedulerCore core) {
		this.getter = getter;
		this.core = core;
		this.key = new RegisterMeSortKey(getter.getPriorityClass());
	}
	
	public void objectOnActivate(ObjectContainer container) {
		container.activate(key, 1);
	}
}

