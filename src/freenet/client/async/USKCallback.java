/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.keys.USK;

/**
 * USK callback interface. Used for subscriptions to the USKManager,
 * and extended for USKFetcher callers.
 */
public interface USKCallback {

	/** Found the latest edition.
	 * @param l The edition number.
	 * @param key The key. */
	void onFoundEdition(long l, USK key);
	
}
