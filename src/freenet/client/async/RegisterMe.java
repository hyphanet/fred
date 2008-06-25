package freenet.client.async;

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
}

