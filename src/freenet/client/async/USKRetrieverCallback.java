package freenet.client.async;

import freenet.client.FetchResult;

/**
 * Interface implemented by USKRetriever clients.
 */
public interface USKRetrieverCallback {
	
	/**
	 * Called when a new edition is found and downloaded.
	 * @param edition The USK edition number.
	 * @param data The retrieved data.
	 */
	void onFound(long edition, FetchResult data);

}
