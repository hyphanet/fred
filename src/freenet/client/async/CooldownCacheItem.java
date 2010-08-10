package freenet.client.async;

public class CooldownCacheItem {
	
	CooldownCacheItem(long wakeupTime) {
		this.timeValid = wakeupTime;
		// TODO Auto-generated constructor stub
	}

	/** The HasCooldownCacheItem will exit cooldown at this time, and *should* be able to
	 * send a request, although it may not if requests have not yet completed - whether
	 * they were started by it or are coalesced because they have the same key. */
	long timeValid;

}
