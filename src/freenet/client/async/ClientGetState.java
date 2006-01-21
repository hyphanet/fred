package freenet.client.async;

/**
 * A ClientGetState.
 * Represents a stage in the fetch process.
 */
public abstract class ClientGetState {

	public abstract ClientGet getParent();

	public abstract void schedule();

}
