package freenet.client.async;

import freenet.client.FetcherContext;
import freenet.keys.ClientCHK;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.support.Logger;

public abstract class BaseSingleFileFetcher implements SendableGet {

	final ClientKey key;
	protected boolean cancelled;
	final int maxRetries;
	private int retryCount;
	final FetcherContext ctx;
	final ClientRequester parent;

	BaseSingleFileFetcher(ClientKey key, int maxRetries, FetcherContext ctx, ClientRequester parent) {
		retryCount = 0;
		this.maxRetries = maxRetries;
		this.key = key;
		this.ctx = ctx;
		this.parent = parent;
	}
	
	public ClientKey getKey() {
		return key;
	}

	/** Do the request, blocking. Called by RequestStarter. */
	public void send(Node node) {
		if(cancelled) {
			onFailure(new LowLevelGetException(LowLevelGetException.CANCELLED));
			return;
		}
		// Do we need to support the last 3?
		ClientKeyBlock block;
		try {
			block = node.realGetKey(key, ctx.localRequestOnly, ctx.cacheLocalRequests, ctx.ignoreStore);
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

	/** Try again - returns true if we can retry */
	protected boolean retry() {
		if(retryCount <= maxRetries || maxRetries == -1) {
			retryCount++;
			schedule();
			return true;
		}
		return false;
	}

	public void schedule() {
		Logger.minor(this, "Scheduling "+this);
		if(key instanceof ClientCHK)
			parent.chkScheduler.register(this);
		else if(key instanceof ClientSSK)
			parent.sskScheduler.register(this);
		else
			throw new IllegalStateException(String.valueOf(key));
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
		// TODO Auto-generated method stub
		return false;
	}

	public synchronized void cancel() {
		cancelled = true;
	}

	public boolean isFinished() {
		return cancelled;
	}
	
	public Object getClient() {
		return parent.getClient();
	}

}
