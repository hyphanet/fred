/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertException;
import freenet.keys.FreenetURI;

/**
 * A client process. Something that initiates requests, and can cancel them. FCP, FProxy, and the
 * GlobalPersistentClient, implement this somewhere.
 */
public interface ClientCallback {
	// GET / FETCH
	/** Called on successful fetch */
	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container);

	/** Called on failed/canceled fetch */
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container);

	// PUT / INSERT
	/** Called when URI is know (e.g. after encode all CHK blocks) */
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container);

	/** Called when the inserted data is fetchable (just a hints, don't rely on this) */
	public void onFetchable(BaseClientPutter state, ObjectContainer container);

	/** Called on successful fetch */
	public void onSuccess(BaseClientPutter state, ObjectContainer container);

	/** Called on failed/canceled fetch */
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container);

	// COMMON
	/**
	 * Called when freenet.async thinks that the request should be serialized to disk, if it is a
	 * persistent request.
	 */
	public void onMajorProgress(ObjectContainer container);
}
