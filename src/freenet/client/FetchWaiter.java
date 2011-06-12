/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;

/** Provides a blocking wrapper for a fetch. Used for simple blocking APIs such as HighLevelSimpleClient. */
public class FetchWaiter implements ClientGetCallback {

	private FetchResult result;
	private FetchException error;
	private boolean finished;
	
	@Override
	public synchronized void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		if(finished) return;
		this.result = result;
		finished = true;
		notifyAll();
	}

	@Override
	public synchronized void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		if(finished) return;
		this.error = e;
		finished = true;
		notifyAll();
	}

	/** Wait for the request to complete, return the results, throw if it failed. */
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

	@Override
	public void onMajorProgress(ObjectContainer container) {
		// Ignore
	}
}
