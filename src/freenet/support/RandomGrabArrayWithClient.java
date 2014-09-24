package freenet.support;

import freenet.client.async.ClientRequestSelector;

public class RandomGrabArrayWithClient<T> extends RandomGrabArray implements RemoveRandomWithObject<T> {

	private T client;
	
	public RandomGrabArrayWithClient(T client, RemoveRandomParent parent, ClientRequestSelector root) {
		super(parent, root);
		this.client = client;
	}

	@Override
	public final T getObject() {
	    synchronized(root) {
	        return client;
	    }
	}

	@Override
	public void setObject(T client) {
	    synchronized(root) {
	        this.client = client;
	    }
	}
	
}
