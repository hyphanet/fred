/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertException;
import freenet.keys.FreenetURI;

/**
 * A client process. Something that initiates requests, and can cancel
 * them. FCP, FProxy, and the GlobalPersistentClient, implement this
 * somewhere.
 */
public interface ClientCallback {

	public void onSuccess(FetchResult result, ClientGetter state);
	
	public void onFailure(FetchException e, ClientGetter state);

	public void onSuccess(BaseClientPutter state);
	
	public void onFailure(InsertException e, BaseClientPutter state);
	
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state);
	
	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public void onMajorProgress();

	/** Called when the inserted data is fetchable (don't rely on this) */
	public void onFetchable(BaseClientPutter state);
}
