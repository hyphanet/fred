/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

public class CooldownCacheItem {

    /**
     * The HasCooldownCacheItem will exit cooldown at this time, and *should* be able to
     * send a request, although it may not if requests have not yet completed - whether
     * they were started by it or are coalesced because they have the same key.
     */
    long timeValid;

    CooldownCacheItem(long wakeupTime) {
        this.timeValid = wakeupTime;

        // TODO Auto-generated constructor stub
    }
}
