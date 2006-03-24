package freenet.client.async;

import java.util.HashMap;

import freenet.client.FetcherContext;
import freenet.keys.USK;
import freenet.support.Logger;

/**
 * Tracks the latest version of every known USK.
 * Also does auto-updates.
 */
public class USKManager {

	/** Latest version by blanked-edition-number USK */
	final HashMap latestVersionByClearUSK;
	
	/** Subscribers by clear USK */
	final HashMap subscribersByClearUSK;
	
	/** USKFetcher's by USK. USK includes suggested edition number, so there is one
	 * USKFetcher for each {USK, edition number}. */
	final HashMap fetchersByUSK;
	
	/** USKChecker's by USK. Deleted immediately on completion. */
	final HashMap checkersByUSK;
	
	public USKManager() {
		latestVersionByClearUSK = new HashMap();
		subscribersByClearUSK = new HashMap();
		fetchersByUSK = new HashMap();
		checkersByUSK = new HashMap();
	}
	
	/**
	 * Look up the latest known version of the given USK.
	 * @return The latest known edition number, or -1.
	 */
	public synchronized long lookup(USK usk) {
		Long l = (Long) latestVersionByClearUSK.get(usk.clearCopy());
		if(l != null)
			return l.longValue();
		else return -1;
	}

	public synchronized USKFetcher getFetcher(USK usk, FetcherContext ctx,
			ClientGetter parent) {
		USKFetcher f = (USKFetcher) fetchersByUSK.get(usk);
		if(f != null) {
			if(f.parent.priorityClass == parent.priorityClass && f.ctx.equals(ctx))
				return f;
		}
		f = new USKFetcher(usk, this, ctx, parent);
		fetchersByUSK.put(usk, f);
		return f;
	}

	synchronized void finished(USKFetcher f) {
		USK u = f.getOriginalUSK();
		fetchersByUSK.remove(u);
	}
	
	void update(USK origUSK, long number) {
		Logger.minor(this, "Updating "+origUSK.getURI()+" : "+number);
		USK clear = origUSK.clearCopy();
		USKCallback[] callbacks;
		synchronized(this) {
			Long l = (Long) latestVersionByClearUSK.get(clear);
			Logger.minor(this, "Old value: "+l);
			if(!(l != null && l.longValue() > number)) {
				l = new Long(number);
				latestVersionByClearUSK.put(clear, l);
				Logger.minor(this, "Put "+number);
			} else return;
			callbacks = (USKCallback[]) subscribersByClearUSK.get(clear);
		}
		if(callbacks != null) {
			USK usk = origUSK.copy(number);
			for(int i=0;i<callbacks.length;i++)
				callbacks[i].onFoundEdition(number, usk);
		}
	}
	
	/**
	 * Subscribe to a given USK. Callback will be notified when it is
	 * updated. Note that this does not imply that the USK will be
	 * checked on a regular basis!
	 */
	public synchronized void subscribe(USK origUSK, USKCallback cb) {
		USK clear = origUSK.clearCopy();
		USKCallback[] callbacks = (USKCallback[]) subscribersByClearUSK.get(clear);
		if(callbacks == null)
			callbacks = new USKCallback[1];
		else
			callbacks = new USKCallback[callbacks.length+1];
		callbacks[callbacks.length-1] = cb;
	}
}
