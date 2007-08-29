package freenet.support;

import freenet.crypt.RandomSource;

public class SectoredRandomGrabArrayWithObject extends SectoredRandomGrabArray implements RemoveRandomWithObject {

	private final Object object;
	
	public SectoredRandomGrabArrayWithObject(Object object, RandomSource rand) {
		super(rand);
		this.object = object;
	}

	public Object getObject() {
		return object;
	}
	
	public String toString() {
		return super.toString()+":"+object;
	}
	
}
