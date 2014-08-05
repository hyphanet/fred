package freenet.support;

public class RandomGrabArrayWithClient extends RandomGrabArray implements RemoveRandomWithObject {

	private Object client;
	
	public RandomGrabArrayWithClient(Object client, boolean persistent, RemoveRandomParent parent) {
		super(persistent, parent);
		this.client = client;
	}

	@Override
	public final Object getObject() {
		return client;
	}

	@Override
	public void setObject(Object client) {
		this.client = client;
	}
}
