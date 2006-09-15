/*
  USKFetcherWrapper.java / Freenet
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
