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

	/**
	 * Found the latest edition. Called when the USKFetcher has finished, it won't
	 * search for any later editions. 
	 * 
	 * @param l
	 *            The edition number.
	 * @param key
	 *            A copy of the key with new edition set
	 * @param newKnownGood If the highest known good edition (which has actually been
	 * fetched with what it pointed to) has increased. Otherwise, the highest known
	 * SSK slot has been increased, from which searches will start, but we do not 
	 * know whether it can actually be fetched successfully.
	 * @param newSlotToo If newKnownGood is set, this indicates whether it is also a
	 * new highest known SSK slot. If newKnownGood is not set, there is always a new
	 * highest known SSK slot.
	 */
	void onFoundEdition(long l, USK key, ClientContext context, boolean metadata, short codec, byte[] data, boolean newKnownGood, boolean newSlotToo);
	
	/**
	 * Priority at which the polling should run normally. See RequestScheduler for constants.
	 */
	short getPollingPriorityNormal();
	
	/**
	 * Priority at which the polling should run when starting, or immediately after making some progress.
	 * See RequestScheduler for constants.
	 */
	short getPollingPriorityProgress();
	
}
