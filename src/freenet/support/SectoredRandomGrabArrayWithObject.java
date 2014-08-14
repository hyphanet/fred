package freenet.support;

public class SectoredRandomGrabArrayWithObject extends SectoredRandomGrabArray implements RemoveRandomWithObject {

	private Object object;
	
	public SectoredRandomGrabArrayWithObject(Object object, RemoveRandomParent parent) {
		super(parent);
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
