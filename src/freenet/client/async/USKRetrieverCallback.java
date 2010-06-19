/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.FetchResult;
import freenet.keys.USK;
import freenet.node.RequestStarter;

/**
 * Interface implemented by USKRetriever clients.
 */
public interface USKRetrieverCallback {
	
	/**
	 * Called when a new edition is found and downloaded.
	 * @param edition The USK edition number.
	 * @param data The retrieved data.
	 */
	void onFound(USK origUSK, long edition, FetchResult data);

	/**
	 * Priority at which the polling should run normally.
	 * You have to return one of the constants from {@link RequestStarter}.
	 */
	short getPollingPriorityNormal();

	/**
	 * Priority at which the polling should run when starting, or immediately after making some progress.
	 *  You have to return one of the constants from {@link RequestStarter}.
	 */
	short getPollingPriorityProgress();

}
