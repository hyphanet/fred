package freenet.support;

import com.db4o.ObjectContainer;

public interface RemoveRandomWithObject extends RemoveRandom {

	public Object getObject();

	public boolean isEmpty();

	public void removeFrom(ObjectContainer container);

}
