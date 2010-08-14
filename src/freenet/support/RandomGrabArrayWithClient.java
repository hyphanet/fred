package freenet.support;

import com.db4o.ObjectContainer;

public class RandomGrabArrayWithClient extends RandomGrabArray implements RemoveRandomWithObject {

	private Object client;
	
	public RandomGrabArrayWithClient(Object client, boolean persistent, ObjectContainer container, RemoveRandomParent parent) {
		super(persistent, container, parent);
		this.client = client;
	}

	public final Object getObject() {
		return client;
	}

	public void setObject(Object client, ObjectContainer container) {
		this.client = client;
		if(persistent) container.store(this);
	}
}
