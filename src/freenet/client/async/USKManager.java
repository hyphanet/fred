package freenet.client.async;

import java.util.HashMap;
import java.util.LinkedList;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetcherContext;
import freenet.keys.ClientSSK;
import freenet.keys.USK;
import freenet.support.Bucket;
import freenet.support.Logger;

/**
 * Tracks the latest version of every known USK.
 * Also does auto-updates.
 */
public class USKManager {

	/** Latest version by blanked-edition-number USK */
	final HashMap latestVersionByClearUSK;
	
	/** USKFetcher's by USK. USK includes suggested edition number, so there is one
	 * USKFetcher for each {USK, edition number}. */
	final HashMap fetchersByUSK;
	
	/** USKChecker's by USK. Deleted immediately on completion. */
	final HashMap checkersByUSK;
	
	public USKManager() {
		latestVersionByClearUSK = new HashMap();
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
	
	synchronized void update(USK origUSK, long number) {
		Logger.minor(this, "Updating "+origUSK.getURI()+" : "+number);
		USK clear = origUSK.clearCopy();
		Long l = (Long) latestVersionByClearUSK.get(clear);
		Logger.minor(this, "Old value: "+l);
		if(!(l != null && l.longValue() > number)) {
			l = new Long(number);
			latestVersionByClearUSK.put(clear, l);
			Logger.minor(this, "Put "+number);
		}
	}
}
