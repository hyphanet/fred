package freenet.client.async;

import freenet.node.SendableRequest;

public class RegisterMe {
	final SendableRequest getter;
	final ClientRequestSchedulerCore core;
	final short priority;
	final long addedTime;
	
	RegisterMe(SendableRequest getter, ClientRequestSchedulerCore core) {
		this.getter = getter;
		this.core = core;
		this.addedTime = System.currentTimeMillis();
		this.priority = getter.getPriorityClass();
	}
}

