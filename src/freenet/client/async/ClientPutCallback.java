/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.InsertException;
import freenet.keys.FreenetURI;

public interface ClientPutCallback extends ClientBaseCallback {
	/** Called when URI is know (e.g. after encode all CHK blocks) */
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container);

	/** Called when the inserted data is fetchable (just a hints, don't rely on this) */
	public void onFetchable(BaseClientPutter state, ObjectContainer container);

	/** Called on successful fetch */
	public void onSuccess(BaseClientPutter state, ObjectContainer container);

	/** Called on failed/canceled fetch */
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container);
}
