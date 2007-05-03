package freenet.support;

import freenet.crypt.RandomSource;

public class RandomGrabArrayWithClient extends RandomGrabArray implements RemoveRandomWithObject {

	final Object client;
	
	public RandomGrabArrayWithClient(Object client, RandomSource rand) {
		super(rand);
		this.client = client;
	}

	public final Object getObject() {
		return client;
	}
}
