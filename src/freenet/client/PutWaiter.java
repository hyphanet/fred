/*
  PutWaiter.java / Freenet
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
import freenet.support.Logger;

public class PutWaiter implements ClientCallback {

	private boolean finished;
	private boolean succeeded;
	private FreenetURI uri;
	private InserterException error;
	
	public void onSuccess(FetchResult result, ClientGetter state) {
		// Ignore
	}

	public void onFailure(FetchException e, ClientGetter state) {
		// Ignore
	}

	public synchronized void onSuccess(BaseClientPutter state) {
		succeeded = true;
		finished = true;
		notifyAll();
	}

	public synchronized void onFailure(InserterException e, BaseClientPutter state) {
		error = e;
		finished = true;
		notifyAll();
	}

	public synchronized void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "URI: "+uri);
		if(this.uri == null)
			this.uri = uri;
		if(uri.equals(this.uri)) return;
		Logger.error(this, "URI already set: "+this.uri+" but new URI: "+uri, new Exception("error"));
	}

	public synchronized FreenetURI waitForCompletion() throws InserterException {
		while(!finished) {
			try {
				wait();
			} catch (InterruptedException e) {
				// Ignore
			}
		}
		if(error != null) {
			error.uri = uri;
			throw error;
		}
		if(succeeded) return uri;
		Logger.error(this, "Did not succeed but no error");
		throw new InserterException(InserterException.INTERNAL_ERROR, "Did not succeed but no error", uri);
	}

	public void onMajorProgress() {
		// Ignore
	}

	public void onFetchable(BaseClientPutter state) {
		// Ignore
	}

}
