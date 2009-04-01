package freenet.support;

import com.db4o.ObjectContainer;

public class RandomGrabArrayWithClient extends RandomGrabArray implements RemoveRandomWithObject {

	final Object client;
	
	public RandomGrabArrayWithClient(Object client, boolean persistent, ObjectContainer container, RemoveRandomParent parent) {
		super(persistent, container, parent);
		this.client = client;
	}

	public final Object getObject() {
		return client;
	}
}
