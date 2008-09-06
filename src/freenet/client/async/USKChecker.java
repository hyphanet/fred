/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSKBlock;
import freenet.node.LowLevelGetException;
import freenet.node.RequestScheduler;
import freenet.support.Logger;

/**
 * Checks a single USK slot.
 */
class USKChecker extends BaseSingleFileFetcher {

	final USKCheckerCallback cb;
	private int dnfs;

	USKChecker(USKCheckerCallback cb, ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent) {
		super(key, maxRetries, ctx, parent);
        if(Logger.shouldLog(Logger.MINOR, this))
        	Logger.minor(this, "Created USKChecker for "+key);
		this.cb = cb;
	}
	
	@Override
	public void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, RequestScheduler sched) {
		unregister(false);
		cb.onSuccess((ClientSSKBlock)block);
	}

	@Override
	public void onFailure(LowLevelGetException e, Object token, RequestScheduler sched) {
        if(Logger.shouldLog(Logger.MINOR, this))
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

		if(canRetry && retry(sched, ctx.executor)) return;
		
		// Ran out of retries.
		unregister(false);
		if(e.code == LowLevelGetException.CANCELLED){
			cb.onCancelled();
			return;
		}else if(e.code == LowLevelGetException.DECODE_FAILED){
			cb.onFatalAuthorError();
			return;
		}
		// Rest are non-fatal. If have DNFs, DNF, else network error.
		if(dnfs > 0)
			cb.onDNF();
		else
			cb.onNetworkError();
	}

	@Override
	public String toString() {
		return "USKChecker for "+key.getURI()+" for "+cb;
	}

	@Override
	public short getPriorityClass() {
		return cb.getPriority();
	}
}
