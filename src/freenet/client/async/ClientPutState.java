package freenet.client.async;

import freenet.client.InserterException;
import freenet.support.SimpleFieldSet;

/**
 * ClientPutState
 * 
 * Represents a state in the insert process.
 */
public interface ClientPutState {

	/** Get the BaseClientPutter responsible for this request state. */
	public abstract BaseClientPutter getParent();

	/** Cancel the request. */
	public abstract void cancel();

	/** Schedule the request. */
	public abstract void schedule() throws InserterException;
	
	/**
	 * Get the token, an object which is passed around with the insert and may be
	 * used by callers.
	 */
	public Object getToken();

	/** Serialize current progress to a SimpleFieldSet.
	 * Does not have to be complete! */
	public abstract SimpleFieldSet getProgressFieldset();
}
