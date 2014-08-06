package freenet.client.async;

public interface Encodeable {

	/** Attempt to encode the block, if necessary */
	public void tryEncode(ClientContext context);

	public boolean persistent();

	public short getPriorityClass();
	
}
