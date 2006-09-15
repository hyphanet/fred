/*
  FetchWaiter.java / Freenet
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

package freenet.client;

import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;

public class FetchWaiter implements ClientCallback {

	private FetchResult result;
	private FetchException error;
	private boolean finished;
	
	public synchronized void onSuccess(FetchResult result, ClientGetter state) {
		if(finished) return;
		this.result = result;
		finished = true;
		notifyAll();
	}

	public synchronized void onFailure(FetchException e, ClientGetter state) {
		if(finished) return;
		this.error = e;
		finished = true;
		notifyAll();
	}

	public void onSuccess(BaseClientPutter state) {
		throw new UnsupportedOperationException();
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		throw new UnsupportedOperationException();
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		throw new UnsupportedOperationException();
	}

	public synchronized FetchResult waitForCompletion() throws FetchException {
		while(!finished) {
			try {
				wait();
			} catch (InterruptedException e) {
				// Ignore
			}
		}

		if(error != null) throw error;
		return result;
	}

	public void onMajorProgress() {
		// Ignore
	}

	public void onFetchable(BaseClientPutter state) {
		// Ignore
	}
}
