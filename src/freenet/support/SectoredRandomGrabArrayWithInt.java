package freenet.support;

import com.db4o.ObjectContainer;

public class SectoredRandomGrabArrayWithInt extends SectoredRandomGrabArray implements IntNumberedItem {

	private final int number;

	public SectoredRandomGrabArrayWithInt(int number, boolean persistent, ObjectContainer container, RemoveRandomParent parent) {
		super(persistent, container, parent);
		this.number = number;
	}

	@Override
	public int getNumber() {
		return number;
	}
	
	@Override
	public String toString() {
		return super.toString() + ":"+number;
	}

}
