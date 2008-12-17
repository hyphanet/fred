/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.USK;

/**
 * USK callback interface. Used for subscriptions to the USKManager,
 * and extended for USKFetcher callers.
 */
public interface USKCallback {

	/**
	 * Found the latest edition.
	 * 
	 * @param l
	 *            The edition number.
	 * @param key
	 *            A copy of the key with new edition set
	 */
	void onFoundEdition(long l, USK key, ObjectContainer container, ClientContext context, boolean metadata, short codec, byte[] data);
	
	/**
	 * Priority at which the polling should run normally.
	 */
	short getPollingPriorityNormal();
	
	/**
	 * Priority at which the polling should run when starting, or immediately after making some progress.
	 */
	short getPollingPriorityProgress();
	
}
