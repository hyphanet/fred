package freenet.support;

import freenet.crypt.RandomSource;

public class RandomGrabArrayWithInt extends RandomGrabArray implements IntNumberedItem {

	private final int number;

	public RandomGrabArrayWithInt(RandomSource rand, int no, boolean persistent) {
		super(rand, persistent);
		number = no;
	}
	
	public int getNumber() {
		return number;
	}

}
