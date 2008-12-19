/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetchContext;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.RequestClient;
import freenet.support.Logger;

/**
 * Poll a USK, and when a new slot is found, fetch it. 
 */
public class USKRetriever extends BaseClientGetter implements USKCallback {

	/** Context for fetching data */
	final FetchContext ctx;
	final USKRetrieverCallback cb;
	
	public USKRetriever(FetchContext fctx, short prio,  
			RequestClient client, USKRetrieverCallback cb) {
		super(prio, client);
		if(client.persistent()) throw new UnsupportedOperationException("USKRetriever cannot be persistent");
		this.ctx = fctx;
		this.cb = cb;
	}

	public void onFoundEdition(long l, USK key, ObjectContainer container, ClientContext context, boolean metadata, short codec, byte[] data) {
		if(l < 0) {
			Logger.error(this, "Found negative edition: "+l+" for "+key+" !!!");
			return;
		}
		// Create a SingleFileFetcher for the key (as an SSK).
		// Put the edition number into its context object.
		// Put ourself as callback.
		// Fetch it. If it fails, ignore it, if it succeeds, return the data with the edition # to the client.
		FreenetURI uri = key.getSSK(l).getURI();
		try {
			SingleFileFetcher getter =
				(SingleFileFetcher) SingleFileFetcher.create(this, this, uri, ctx, new ArchiveContext(ctx.maxTempLength, ctx.maxArchiveLevels), 
						ctx.maxNonSplitfileRetries, 0, true, l, true, null, false, null, context);
			getter.schedule(null, context);
		} catch (MalformedURLException e) {
			Logger.error(this, "Impossible: "+e, e);
		} catch (FetchException e) {
			Logger.error(this, "Could not start fetcher for "+uri+" : "+e, e);
		}
	}

	public void onSuccess(FetchResult result, ClientGetState state, ObjectContainer container, ClientContext context) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Success on "+this+" from "+state+" : length "+result.size()+" mime type "+result.getMimeType());
		cb.onFound(state.getToken(), result);
	}

	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
		Logger.error(this, "Found edition "+state.getToken()+" but failed to fetch edition: "+e, e);
	}

	public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public FreenetURI getURI() {
		return null;
	}

	@Override
	public boolean isFinished() {
		return false;
	}

	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		// Ignore for now
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

	public void onExpectedMIME(String mime, ObjectContainer container) {
		// Ignore
	}

	public void onExpectedSize(long size, ObjectContainer container) {
		// Ignore
	}

	public void onFinalizedMetadata(ObjectContainer container) {
		// Ignore
	}

	public short getPollingPriorityNormal() {
		return cb.getPollingPriorityNormal();
	}

	public short getPollingPriorityProgress() {
		return cb.getPollingPriorityProgress();
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		super.cancel();
	}

}
