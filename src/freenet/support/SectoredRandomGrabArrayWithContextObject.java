package freenet.support;

import freenet.crypt.RandomSource;

public class SectoredRandomGrabArrayWithContextObject extends SectoredRandomGrabArray implements RemoveRandomWithObject {

	private final Object object;
	
	public SectoredRandomGrabArrayWithContextObject(Object object, RandomSource rand) {
		super(rand);
		this.object = object;
	}

	public Object getObject() {
		return object;
	}
	
}
