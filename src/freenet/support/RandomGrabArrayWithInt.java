package freenet.support;

import com.db4o.ObjectContainer;

public class RandomGrabArrayWithInt extends RandomGrabArray implements IntNumberedItem {

	private final int number;

	public RandomGrabArrayWithInt(int no, boolean persistent, ObjectContainer container) {
		super(persistent, container);
		number = no;
	}
	
	public int getNumber() {
		return number;
	}

}
