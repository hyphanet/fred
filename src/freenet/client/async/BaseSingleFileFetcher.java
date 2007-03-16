/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.ClientSSK;
import freenet.node.SendableGet;
import freenet.support.Logger;
import freenet.support.RandomGrabArray;

public abstract class BaseSingleFileFetcher extends SendableGet {

	final ClientKey key;
	protected boolean cancelled;
	final int maxRetries;
	private int retryCount;
	final FetchContext ctx;
	static final int[] keys = new int[] { 0 };

	BaseSingleFileFetcher(ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent) {
		super(parent);
		retryCount = 0;
		this.maxRetries = maxRetries;
		this.key = key;
		this.ctx = ctx;
	}

	public int[] allKeys() {
		return keys;
	}
	
	public int chooseKey() {
		return 0;
	}
	
	public ClientKey getKey(int token) {
		return key;
	}
	
	public FetchContext getContext() {
		return ctx;
	}

	public boolean isSSK() {
		return key instanceof ClientSSK;
	}

	/** Try again - returns true if we can retry */
	protected boolean retry() {
		retryCount++;
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Attempting to retry... (max "+maxRetries+", current "+retryCount+ ')');
		// We want 0, 1, ... maxRetries i.e. maxRetries+1 attempts (maxRetries=0 => try once, no retries, maxRetries=1 = original try + 1 retry)
		if((retryCount <= maxRetries) || (maxRetries == -1)) {
			schedule();
			return true;
		}
		return false;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public ClientRequester getClientRequest() {
		return parent;
	}

	public short getPriorityClass() {
		return parent.getPriorityClass();
	}

	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	public void cancel() {
		synchronized(this) {
			cancelled = true;
		}
		RandomGrabArray arr = getParentGrabArray();
		if(arr != null) arr.remove(this);
	}

	public synchronized boolean isCancelled() {
		return cancelled;
	}
	
	public Object getClient() {
		return parent.getClient();
	}

	public boolean dontCache() {
		return !ctx.cacheLocalRequests;
	}
	
	public boolean canRemove() {
		// Simple request, once it's sent, it's sent. May be requeued at a different # retries.
		return true;
	}

}
