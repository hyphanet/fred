package freenet.client.async;

/**
 * A ClientGetState.
 * Represents a stage in the fetch process.
 */
public abstract interface ClientGetState {

	public void schedule();

	public void cancel();

	public Object getToken();
}
