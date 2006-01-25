package freenet.support;

import freenet.crypt.RandomSource;

public class RandomGrabArrayWithInt extends RandomGrabArray implements IntNumberedItem {

	private final int number;

	public RandomGrabArrayWithInt(RandomSource rand, int no) {
		super(rand);
		number = no;
	}
	
	public int getNumber() {
		return number;
	}

}
