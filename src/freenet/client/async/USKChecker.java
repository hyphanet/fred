/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSKBlock;
import freenet.node.LowLevelGetException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Checks a single USK slot.
 */
class USKChecker extends BaseSingleFileFetcher {

	final USKCheckerCallback cb;
	private int dnfs;
	
	private long cooldownWakeupTime;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	USKChecker(USKCheckerCallback cb, ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent, boolean realTimeFlag) {
		super(key, maxRetries, ctx, parent, false, realTimeFlag);
		this.cb = cb;
        if(logMINOR)
            Logger.minor(USKChecker.class, "Created USKChecker for "+key+" : "+this);
	}
	
	@Override
	public void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(cb, 1);
		}
		// No need to check from here since USKFetcher will be told anyway.
		cb.onSuccess((ClientSSKBlock)block, context);
	}

	@Override
	public void onFailure(LowLevelGetException e, Object token, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(cb, 1);
		}
                if(logMINOR)
                    Logger.minor(this, "onFailure: "+e+" for "+this);
		// Firstly, can we retry?
		boolean canRetry;
		switch(e.code) {
		case LowLevelGetException.CANCELLED:
		case LowLevelGetException.DECODE_FAILED:
			// Cannot retry
			canRetry = false;
			break;
		case LowLevelGetException.DATA_NOT_FOUND:
		case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
		case LowLevelGetException.RECENTLY_FAILED:
			dnfs++;
			canRetry = true;
			break;
		case LowLevelGetException.INTERNAL_ERROR:
		case LowLevelGetException.REJECTED_OVERLOAD:
		case LowLevelGetException.ROUTE_NOT_FOUND:
		case LowLevelGetException.TRANSFER_FAILED:
		case LowLevelGetException.VERIFY_FAILED:
			// Can retry
			canRetry = true;
			break;
		default:
			Logger.error(this, "Unknown low-level fetch error code: "+e.code, new Exception("error"));
			canRetry = true;
		}

		if(canRetry && retry(container, context)) return;
		
		// Ran out of retries.
		unregisterAll(container, context);
		if(e.code == LowLevelGetException.CANCELLED){
			cb.onCancelled(context);
			return;
		}else if(e.code == LowLevelGetException.DECODE_FAILED){
			cb.onFatalAuthorError(context);
			return;
		}
		// Rest are non-fatal. If have DNFs, DNF, else network error.
		if(dnfs > 0)
			cb.onDNF(context);
		else
			cb.onNetworkError(context);
	}

	@Override
	protected String transientToString() {
		return "USKChecker for "+key.getURI()+" for "+cb;
	}

	@Override
	public void onFailed(KeyListenerConstructionException e, ObjectContainer container, ClientContext context) {
		onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR, "IMPOSSIBLE: Failed to create Bloom filters (we don't have any!)", e), null, container, context);
	}
	
	@Override
	public short getPriorityClass(ObjectContainer container) {
		return cb.getPriority();
	}
	
	@Override
	protected void onEnterFiniteCooldown(ClientContext context) {
		cb.onEnterFiniteCooldown(context);
	}

	@Override
	protected void notFoundInStore(ObjectContainer container,
			ClientContext context) {
		// Ran out of retries.
		unregisterAll(container, context);
		// Rest are non-fatal. If have DNFs, DNF, else network error.
		cb.onDNF(context);
	}

	@Override
	protected void onBlockDecodeError(Object token, ObjectContainer container,
			ClientContext context) {
		onFailure(new LowLevelGetException(LowLevelGetException.DECODE_FAILED), token, container, context);
	}

}
