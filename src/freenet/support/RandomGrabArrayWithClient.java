package freenet.support;

import freenet.crypt.RandomSource;

public class RandomGrabArrayWithClient extends RandomGrabArray {

	final Object client;
	
	public RandomGrabArrayWithClient(Object client, RandomSource rand) {
		super(rand);
		this.client = client;
	}

}
