/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.client.FetchContext;
import freenet.client.async.ClientRequester;
import freenet.keys.ClientCHK;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.support.Logger;

/**
 * A low-level key fetch which can be sent immediately. @see SendableRequest
 */
public abstract class SendableGet implements SendableRequest {

	/** Parent BaseClientGetter. Required for schedulers. */
	public final ClientRequester parent;
	
	/** Get the key to fetch (this time!). */
	public abstract ClientKey getKey();
	
	/** Get the fetch context (settings) object. */
	public abstract FetchContext getContext();
	
	/** Called when/if the low-level request succeeds. */
	public abstract void onSuccess(ClientKeyBlock block, boolean fromStore);
	
	/** Called when/if the low-level request fails. */
	public abstract void onFailure(LowLevelGetException e);
	
	/** Should the request ignore the datastore? */
	public abstract boolean ignoreStore();

	/** If true, don't cache local requests */
	public abstract boolean dontCache();

	// Implementation

	public SendableGet(ClientRequester parent) {
		this.parent = parent;
	}
	
	/** Do the request, blocking. Called by RequestStarter. */
	public void send(NodeClientCore core) {
		synchronized (this) {
			if(isCancelled()) {
				onFailure(new LowLevelGetException(LowLevelGetException.CANCELLED));
				return;
			}	
		}
		// Do we need to support the last 3?
		ClientKeyBlock block;
		try {
			FetchContext ctx = getContext();
			block = core.realGetKey(getKey(), ctx.localRequestOnly, ctx.cacheLocalRequests, ctx.ignoreStore);
		} catch (LowLevelGetException e) {
			onFailure(e);
			return;
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR));
			return;
		}
		onSuccess(block, false);
	}

	public void schedule() {
		ClientKey key = getKey();
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Scheduling "+this+" for "+key);
		if(key instanceof ClientCHK)
			parent.chkScheduler.register(this);
		else if(key instanceof ClientSSK)
			parent.sskScheduler.register(this);
		else
			throw new IllegalStateException(String.valueOf(key));
	}

}
