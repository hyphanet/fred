package freenet.support;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;

public class RandomGrabArrayWithInt extends RandomGrabArray implements IntNumberedItem {

	private final int number;

	public RandomGrabArrayWithInt(RandomSource rand, int no, boolean persistent, ObjectContainer container) {
		super(rand, persistent, container);
		number = no;
	}
	
	public int getNumber() {
		return number;
	}

}
