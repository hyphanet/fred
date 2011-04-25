/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.keys.ClientSSKBlock;

/**
 * Callback for a USKChecker
 */
interface USKCheckerCallback {

	/** Data Not Found */
	public void onDNF(ClientContext context);
	
	/** Successfully found the latest version of the key 
	 * @param block */
	public void onSuccess(ClientSSKBlock block, ClientContext context);
	
	/** Error committed by author */
	public void onFatalAuthorError(ClientContext context);
	
	/** Network on our node or on nodes we have been talking to */
	public void onNetworkError(ClientContext context);

	/** Request cancelled */
	public void onCancelled(ClientContext context);
	
	/** Get priority to run the request at */
	public short getPriority();

	/** Called when we enter a finite cooldown */
	public void onEnterFiniteCooldown(ClientContext context);
	
}
