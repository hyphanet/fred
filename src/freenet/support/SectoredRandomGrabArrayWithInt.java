package freenet.support;

import freenet.client.async.CooldownTracker;

public class SectoredRandomGrabArrayWithInt extends SectoredRandomGrabArray implements IntNumberedItem {

	private final int number;

	public SectoredRandomGrabArrayWithInt(int number, RemoveRandomParent parent, CooldownTracker tracker) {
		super(parent, tracker);
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
