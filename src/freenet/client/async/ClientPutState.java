package freenet.client.async;

/**
 * ClientPutState
 * 
 * Represents a state in the insert process.
 */
public interface ClientPutState {

	public abstract ClientPutter getParent();

	public abstract void cancel();

}
