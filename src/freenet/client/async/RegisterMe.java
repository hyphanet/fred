package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.node.SendableRequest;

public class RegisterMe {
	final SendableRequest getter;
	final ClientRequestSchedulerCore core;
	final RegisterMeSortKey key;
	
	RegisterMe(SendableRequest getter, short prio, ClientRequestSchedulerCore core) {
		this.getter = getter;
		this.core = core;
		this.key = new RegisterMeSortKey(prio);
	}
	
	public void objectOnActivate(ObjectContainer container) {
		container.activate(key, 1);
	}
}

