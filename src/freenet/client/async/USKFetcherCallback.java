/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

/**
 * Callback interface for USK fetches. If you submit a USK fetch via 
 * USKManager.getFetcher, then register yourself on it as a listener, then you
 * must implement these callback methods.
 */
public interface USKFetcherCallback extends USKCallback {

	/** Failed to find any edition at all (later than or equal to the specified hint) */
	void onFailure();

	void onCancelled();
	
}
