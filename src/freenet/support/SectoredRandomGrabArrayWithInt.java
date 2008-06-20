package freenet.support;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;

public class SectoredRandomGrabArrayWithInt extends SectoredRandomGrabArray implements IntNumberedItem {

	private final int number;

	public SectoredRandomGrabArrayWithInt(RandomSource rand, int number, boolean persistent, ObjectContainer container) {
		super(rand, persistent, container);
		this.number = number;
	}

	public int getNumber() {
		return number;
	}
	
	public String toString() {
		return super.toString() + ":"+number;
	}

}
