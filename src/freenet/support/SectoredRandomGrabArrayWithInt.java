package freenet.support;

import freenet.client.async.ClientRequestSelector;

public class SectoredRandomGrabArrayWithInt<T, C extends RemoveRandomWithObject<T>>
		extends SectoredRandomGrabArray<T, C> implements IntNumberedItem {

	private final int number;

	public SectoredRandomGrabArrayWithInt(int number, RemoveRandomParent parent, ClientRequestSelector root) {
		super(parent, root);
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
