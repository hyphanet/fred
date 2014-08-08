/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.InsertException;

/**
 * ClientPutState
 * 
 * Represents a state in the insert process.
 */
public interface ClientPutState {

	/** Get the BaseClientPutter responsible for this request state. */
	public abstract BaseClientPutter getParent();

	/** Cancel the request. */
	public abstract void cancel(ClientContext context);

	/** Schedule the request. */
	public abstract void schedule(ClientContext context) throws InsertException;
	
	/**
	 * Get the token, an object which is passed around with the insert and may be
	 * used by callers.
	 */
	public Object getToken();
	
    /** Called on restarting the node for a persistent request. The request must re-schedule 
     * itself. 
     * @throws InsertException */
    public void onResume(ClientContext context) throws InsertException;
}
