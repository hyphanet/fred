package freenet.client.async;

public abstract class BaseClientPutter extends ClientRequest {

	protected BaseClientPutter(short priorityClass, ClientRequestScheduler scheduler, Object context) {
		super(priorityClass, scheduler, context);
	}

}
