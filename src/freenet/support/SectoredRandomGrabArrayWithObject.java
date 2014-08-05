package freenet.support;

import com.db4o.ObjectContainer;

public class SectoredRandomGrabArrayWithObject extends SectoredRandomGrabArray implements RemoveRandomWithObject {

	private Object object;
	
	public SectoredRandomGrabArrayWithObject(Object object, boolean persistent, ObjectContainer container, RemoveRandomParent parent) {
		super(persistent, parent);
		this.object = object;
	}

	@Override
	public Object getObject() {
		return object;
	}
	
	@Override
	public String toString() {
		return super.toString()+":"+object;
	}

	@Override
	public void setObject(Object client) {
		object = client;
	}

}
