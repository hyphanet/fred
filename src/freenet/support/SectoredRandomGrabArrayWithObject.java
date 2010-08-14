package freenet.support;

import com.db4o.ObjectContainer;

public class SectoredRandomGrabArrayWithObject extends SectoredRandomGrabArray implements RemoveRandomWithObject {

	private Object object;
	
	public SectoredRandomGrabArrayWithObject(Object object, boolean persistent, ObjectContainer container, RemoveRandomParent parent) {
		super(persistent, container, parent);
		this.object = object;
	}

	public Object getObject() {
		return object;
	}
	
	@Override
	public String toString() {
		return super.toString()+":"+object;
	}

	public void setObject(Object client, ObjectContainer container) {
		object = client;
		if(persistent) container.store(this);
	}

}
