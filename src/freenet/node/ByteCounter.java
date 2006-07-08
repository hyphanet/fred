package freenet.node;

/**
 * Interface for something which counts bytes.
 */
public interface ByteCounter {
	
	public void sentBytes(int x);
	
	public void receivedBytes(int x);

}
