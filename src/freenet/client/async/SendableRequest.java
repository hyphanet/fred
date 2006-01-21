package freenet.client.async;

/**
 * A low-level request which can be sent immediately. These are registered
 * on the ClientRequestScheduler.
 */
public interface SendableRequest {
	
	public short getPriorityClass();
	
	public int getRetryCount();
	
}
