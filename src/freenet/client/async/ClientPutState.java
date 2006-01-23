package freenet.client.async;

/**
 * ClientPutState
 * 
 * Represents a state in the insert process.
 */
public interface ClientPutState {

	public abstract ClientPut getParent();

	public abstract void cancel();

}
