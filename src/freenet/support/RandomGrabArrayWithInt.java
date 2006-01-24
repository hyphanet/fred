package freenet.support;

public class RandomGrabArrayWithInt extends RandomGrabArray implements IntNumberedItem {

	private final int number;

	public RandomGrabArrayWithInt(int no) {
		number = no;
	}
	
	public int getNumber() {
		return number;
	}

}
