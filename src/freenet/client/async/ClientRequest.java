package freenet.client.async;

/** A high level client request. A request (either fetch or put) started
 * by a Client. Has a suitable context and a URI; is fulfilled only when
 * we have followed all the redirects etc, or have an error. Can be 
 * retried.
 */
public abstract class ClientRequest {

	// FIXME move the priority classes from RequestStarter here
	private short priorityClass;
	protected boolean cancelled;
	
	public short getPriorityClass() {
		return priorityClass;
	}
	
	protected ClientRequest(short priorityClass) {
		this.priorityClass = priorityClass;
	}
	
	public void cancel() {
		cancelled = true;
	}
	
	public boolean isCancelled() {
		return cancelled;
	}
	

}
