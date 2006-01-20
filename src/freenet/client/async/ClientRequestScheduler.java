package freenet.client.async;

/**
 * Every X seconds, the RequestSender calls the ClientRequestScheduler to
 * ask for a request to start. A request is then started, in its own 
 * thread. It is removed at that point.
 */
public class ClientRequestScheduler {

	public void register(SendableRequest req) {
		// FIXME
	}
	
	public void remove(SendableRequest sr) {
		// FIXME
	}
	
	public void update(SendableRequest sr) {
		// FIXME
	}
	
}
