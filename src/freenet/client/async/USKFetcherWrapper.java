package freenet.client.async;

import freenet.keys.FreenetURI;
import freenet.keys.USK;

/**
 * Wrapper for a backgrounded USKFetcher.
 */
public class USKFetcherWrapper extends ClientRequester {

	USK usk;
	
	public USKFetcherWrapper(USK usk, short prio, ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler) {
		super(prio, chkScheduler, sskScheduler, usk);
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
