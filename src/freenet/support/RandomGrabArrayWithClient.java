package freenet.support;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;

public class RandomGrabArrayWithClient extends RandomGrabArray implements RemoveRandomWithObject {

	final Object client;
	
	public RandomGrabArrayWithClient(Object client, RandomSource rand, boolean persistent, ObjectContainer container) {
		super(rand, persistent, container);
		this.client = client;
	}

	public final Object getObject() {
		return client;
	}
}
