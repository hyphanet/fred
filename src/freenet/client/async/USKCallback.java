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
