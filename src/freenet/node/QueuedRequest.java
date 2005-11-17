package freenet.node;

/**
 * A request (including both DataRequest's and InsertRequest's) which can be queued
 * by a RequestStarter.
 */
public abstract class QueuedRequest {

	private boolean clearToSend = false;
	
	/**
	 * Shell for sending the request.
	 */
	public final void clearToSend() {
		synchronized(this) {
			clearToSend = true;
			notifyAll();
		}
	}

	protected void waitForSendClearance() {
		synchronized(this) {
			while(!clearToSend) {
				try {
					wait(10*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
	}
}
