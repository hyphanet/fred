/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.keys.FreenetURI;
import freenet.keys.USK;

public class USKProxyCompletionCallback implements GetCompletionCallback {

	final USK usk;
	final GetCompletionCallback cb;
	
	public USKProxyCompletionCallback(USK usk, GetCompletionCallback cb) {
		this.usk = usk;
		this.cb = cb;
	}

	public void onSuccess(FetchResult result, ClientGetState state, ObjectContainer container, ClientContext context) {
		context.uskManager.update(usk, usk.suggestedEdition);
		cb.onSuccess(result, state, container, context);
	}

	public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
		FreenetURI uri = e.newURI;
		if(uri != null) {
			uri = usk.turnMySSKIntoUSK(uri);
			e = new FetchException(e, uri);
		}
		cb.onFailure(e, state, container, context);
	}

	public void onBlockSetFinished(ClientGetState state, ObjectContainer container) {
		cb.onBlockSetFinished(state, container);
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

	public void onExpectedMIME(String mime, ObjectContainer container) {
		cb.onExpectedMIME(mime, container);
	}

	public void onExpectedSize(long size, ObjectContainer container) {
		cb.onExpectedSize(size, container);
	}

	public void onFinalizedMetadata(ObjectContainer container) {
		cb.onFinalizedMetadata(container);
	}

}
