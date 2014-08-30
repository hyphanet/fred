package freenet.support;

import freenet.client.async.ClientRequestSelector;

public class RandomGrabArrayWithClient extends RandomGrabArray implements RemoveRandomWithObject {

	private Object client;
	
	public RandomGrabArrayWithClient(Object client, RemoveRandomParent parent, ClientRequestSelector root) {
		super(parent, root);
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
