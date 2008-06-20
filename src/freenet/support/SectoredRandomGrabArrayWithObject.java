package freenet.support;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;

public class SectoredRandomGrabArrayWithObject extends SectoredRandomGrabArray implements RemoveRandomWithObject {

	private final Object object;
	
	public SectoredRandomGrabArrayWithObject(Object object, RandomSource rand, boolean persistent, ObjectContainer container) {
		super(rand, persistent, container);
		this.object = object;
	}

	public Object getObject() {
		return object;
	}
	
	public String toString() {
		return super.toString()+":"+object;
	}
	
}
