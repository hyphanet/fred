package freenet.client.async;

import freenet.client.FetcherContext;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSKBlock;
import freenet.node.LowLevelGetException;
import freenet.support.Logger;

/**
 * Checks a single USK slot.
 */
class USKChecker extends BaseSingleFileFetcher {

	final USKCheckerCallback cb;
	private int dnfs;

	USKChecker(USKCheckerCallback cb, ClientKey key, int maxRetries, FetcherContext ctx, ClientRequester parent) {
		super(key, maxRetries, ctx, parent);
        if(Logger.shouldLog(Logger.MINOR, this))
        	Logger.minor(this, "Created USKChecker for "+key);
		this.cb = cb;
	}
	
	public void onSuccess(ClientKeyBlock block, boolean fromStore) {
		cb.onSuccess((ClientSSKBlock)block);
	}

	public void onFailure(LowLevelGetException e) {
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

		if(canRetry && retry()) return;
		
		// Ran out of retries.
		
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

	public String toString() {
		return "USKChecker for "+key.getURI();
	}

	public short getPriorityClass() {
		return cb.getPriority();
	}
}
