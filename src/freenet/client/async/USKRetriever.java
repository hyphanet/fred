/*
  USKRetriever.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
	
	public USKRetriever(FetcherContext fctx, short prio, ClientRequestScheduler chkSched, ClientRequestScheduler sskSched, Object client, USKRetrieverCallback cb) {
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
						ctx.maxNonSplitfileRetries, 0, true, key.copy(l), true, null);
			getter.schedule();
		} catch (MalformedURLException e) {
			Logger.error(this, "Impossible: "+e, e);
		} catch (FetchException e) {
			Logger.error(this, "Could not start fetcher for "+uri+" : "+e, e);
		}
	}

	public void onSuccess(FetchResult result, ClientGetState state) {
		Object token = state.getToken();
		USK key = (USK) token;
		cb.onFound(key.suggestedEdition, result);
	}

	public void onFailure(FetchException e, ClientGetState state) {
		Object token = state.getToken();
		Logger.error(this, "Failed to fetch "+token+" - original insert corrupt?? : "+e, e);
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
