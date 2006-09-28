/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
public interface SendableGet extends SendableRequest {

	public ClientKey getKey();
	
	/** Called when/if the low-level request succeeds. */
	public void onSuccess(ClientKeyBlock block, boolean fromStore);
	
	/** Called when/if the low-level request fails. */
	public void onFailure(LowLevelGetException e);
	
	/** Should the request ignore the datastore? */
	public boolean ignoreStore();

	/** If true, don't cache local requests */
	public boolean dontCache();
}
