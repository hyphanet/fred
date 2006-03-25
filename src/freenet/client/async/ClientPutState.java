package freenet.client.async;

import freenet.client.InserterException;

/**
 * ClientPutState
 * 
 * Represents a state in the insert process.
 */
public interface ClientPutState {

	public abstract BaseClientPutter getParent();

	public abstract void cancel();

	public abstract void schedule() throws InserterException;
}
