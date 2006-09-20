package freenet.client.async;

public abstract class BaseClientGetter extends ClientRequester implements
		GetCompletionCallback {

	protected BaseClientGetter(short priorityClass, ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler, Object client) {
		super(priorityClass, chkScheduler, sskScheduler, client);
	}

	
}
