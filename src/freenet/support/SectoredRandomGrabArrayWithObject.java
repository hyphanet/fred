package freenet.support;

import freenet.client.async.ClientRequestSelector;

public class SectoredRandomGrabArrayWithObject<MyType,ChildType,GrabType extends RemoveRandomWithObject<ChildType>> extends SectoredRandomGrabArray<ChildType,GrabType> implements RemoveRandomWithObject<MyType> {

	private MyType object;
	
	public SectoredRandomGrabArrayWithObject(MyType object, RemoveRandomParent parent, ClientRequestSelector root) {
		super(parent, root);
		this.object = object;
	}

	@Override
	public MyType getObject() {
	    synchronized(root) {
	        return object;
	    }
	}
	
	@Override
	public String toString() {
		return super.toString()+":"+object;
	}

	@Override
	public void setObject(MyType client) {
	    synchronized(root) {
	        object = client;
	    }
	}

}
