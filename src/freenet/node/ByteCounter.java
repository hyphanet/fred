package freenet.node;

/**
 * Interface for something which counts bytes.
 */
public interface ByteCounter {
	
	public void sentBytes(int x);
	
	public void receivedBytes(int x);
	
	/** Sent payload - only include the number of bytes of actual payload i.e. data from the user's point of view, as opposed to overhead */
	public void sentPayload(int x);

}
