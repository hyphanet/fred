package freenet.support;

import com.db4o.ObjectContainer;

public interface RemoveRandomWithObject extends RemoveRandom {

	public Object getObject();

	public boolean isEmpty(ObjectContainer container);

	@Override
	public void removeFrom(ObjectContainer container);

	public void setObject(Object client, ObjectContainer container);

}
