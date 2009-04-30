/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;

public interface ClientGetCallback extends ClientBaseCallback {
	/** Called on successful fetch */
	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container);

	/** Called on failed/canceled fetch */
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container);
}
