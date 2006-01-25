package freenet.client.async;

public abstract class BaseClientPutter extends ClientRequest {

	protected BaseClientPutter(short priorityClass, ClientRequestScheduler scheduler) {
		super(priorityClass, scheduler);
	}

	public abstract void setCurrentState(ClientPutState inserter);

}
