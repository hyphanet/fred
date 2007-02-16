/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.net.MalformedURLException;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.support.Logger;

/**
 * Poll a USK, and when a new slot is found, fetch it. 
 */
public class USKRetriever extends BaseClientGetter implements USKCallback {

	/** Context for fetching data */
	final FetcherContext ctx;
	final USKRetrieverCallback cb;
	
	public USKRetriever(FetcherContext fctx, short prio, ClientRequestScheduler chkSched, 
			ClientRequestScheduler sskSched, Object client, USKRetrieverCallback cb) {
		super(prio, chkSched, sskSched, client);
		this.ctx = fctx;
		this.cb = cb;
	}

	public void onFoundEdition(long l, USK key) {
		// Create a SingleFileFetcher for the key (as an SSK).
		// Put the edition number into its context object.
		// Put ourself as callback.
		// Fetch it. If it fails, ignore it, if it succeeds, return the data with the edition # to the client.
		FreenetURI uri = key.getSSK(l).getURI();
		try {
			SingleFileFetcher getter =
				(SingleFileFetcher) SingleFileFetcher.create(this, this, new ClientMetadata(), uri, ctx, new ArchiveContext(ctx.maxArchiveLevels), 
						ctx.maxNonSplitfileRetries, 0, true, l, true, null, false);
			getter.schedule();
		} catch (MalformedURLException e) {
			Logger.error(this, "Impossible: "+e, e);
		} catch (FetchException e) {
			Logger.error(this, "Could not start fetcher for "+uri+" : "+e, e);
		}
	}

	public void onSuccess(FetchResult result, ClientGetState state) {
		cb.onFound(state.getToken(), result);
	}

	public void onFailure(FetchException e, ClientGetState state) {
		Logger.error(this, "Found edition "+state.getToken()+" but failed to fetch edition: "+e, e);
	}

	public void onBlockSetFinished(ClientGetState state) {
		// Ignore
	}

	public FreenetURI getURI() {
		return null;
	}

	public boolean isFinished() {
		return false;
	}

	public void notifyClients() {
		// Ignore for now
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		// Ignore
	}

}
