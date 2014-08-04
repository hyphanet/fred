package freenet.client.async;

import com.db4o.ObjectContainer;

public interface Encodeable {

	/** Attempt to encode the block, if necessary */
	public void tryEncode(ObjectContainer container, ClientContext context);

	public boolean persistent();

	public short getPriorityClass();
	
}
