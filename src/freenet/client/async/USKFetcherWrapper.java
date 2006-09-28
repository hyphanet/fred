/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.keys.FreenetURI;
import freenet.keys.USK;

/**
 * Wrapper for a backgrounded USKFetcher.
 */
public class USKFetcherWrapper extends BaseClientGetter {

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

	public void onSuccess(FetchResult result, ClientGetState state) {
		// Ignore; we don't do anything with it because we are running in the background.
	}

	public void onFailure(FetchException e, ClientGetState state) {
		// Ignore
	}

	public void onBlockSetFinished(ClientGetState state) {
		// Ignore
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		// Ignore
	}

}
