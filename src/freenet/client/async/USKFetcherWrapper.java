package freenet.client.async;

import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.RequestStarter;

/**
 * Wrapper for a backgrounded USKFetcher.
 */
public class USKFetcherWrapper extends ClientRequester {

	USK usk;
	
	public USKFetcherWrapper(USK usk, ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler) {
		super(RequestStarter.UPDATE_PRIORITY_CLASS, chkScheduler, sskScheduler, usk);
		this.usk = usk;
	}

	public FreenetURI getURI() {
		return usk.getURI();
	}

	public boolean isFinished() {
		return false;
	}

	public void notifyClients() {
		// Do nothing
	}

}
