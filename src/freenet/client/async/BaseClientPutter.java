package freenet.client.async;

public abstract class BaseClientPutter extends ClientRequester {

	protected BaseClientPutter(short priorityClass, ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler, Object context) {
		super(priorityClass, chkScheduler, sskScheduler, context);
	}

}
