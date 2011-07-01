/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.USK;

/**
 * Callback interface for USK fetches. If you submit a USK fetch via 
 * USKManager.getFetcher, then register yourself on it as a listener, then you
 * must implement these callback methods.
 */
public interface USKFetcherCallback extends USKCallback {

	/** Failed to find any edition at all (later than or equal to the specified hint) */
	void onFailure(ObjectContainer container, ClientContext context);

	void onCancelled(ObjectContainer container, ClientContext context);
	
	/** Found the latest edition. **This is terminal for a USKFetcherCallback**. It isn't for a USKCallback subscription.
	 * @param l The edition number.
	 * @param key The key. */
	@Override
	void onFoundEdition(long l, USK key, ObjectContainer container, ClientContext context, boolean metadata, short codec, byte[] data, boolean newKnownGood, boolean newSlotToo);
	
}
