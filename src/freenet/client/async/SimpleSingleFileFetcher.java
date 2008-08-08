/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.keys.TooBigException;
import freenet.node.LowLevelGetException;
import freenet.node.RequestScheduler;
import freenet.support.Logger;
import freenet.support.api.Bucket;

/** 
 * Fetch a single block file.
 * Used by SplitFileFetcherSegment.
 */
public class SimpleSingleFileFetcher extends BaseSingleFileFetcher implements ClientGetState {

	SimpleSingleFileFetcher(ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent, 
			GetCompletionCallback rcb, boolean isEssential, boolean dontAdd, long l) {
		super(key, maxRetries, ctx, parent);
		this.rcb = rcb;
		this.token = l;
		if(!dontAdd) {
			parent.addBlock();
			if(isEssential)
				parent.addMustSucceedBlocks(1);
			parent.notifyClients();
		}
	}

	final GetCompletionCallback rcb;
	final long token;
	
	// Translate it, then call the real onFailure
	public void onFailure(LowLevelGetException e, Object reqTokenIgnored, RequestScheduler sched) {
		switch(e.code) {
		case LowLevelGetException.DATA_NOT_FOUND:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), sched);
			return;
		case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), sched);
			return;
		case LowLevelGetException.RECENTLY_FAILED:
			onFailure(new FetchException(FetchException.RECENTLY_FAILED), sched);
			return;
		case LowLevelGetException.DECODE_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR), sched);
			return;
		case LowLevelGetException.INTERNAL_ERROR:
			onFailure(new FetchException(FetchException.INTERNAL_ERROR), sched);
			return;
		case LowLevelGetException.REJECTED_OVERLOAD:
			onFailure(new FetchException(FetchException.REJECTED_OVERLOAD), sched);
			return;
		case LowLevelGetException.ROUTE_NOT_FOUND:
			onFailure(new FetchException(FetchException.ROUTE_NOT_FOUND), sched);
			return;
		case LowLevelGetException.TRANSFER_FAILED:
			onFailure(new FetchException(FetchException.TRANSFER_FAILED), sched);
			return;
		case LowLevelGetException.VERIFY_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR), sched);
			return;
		case LowLevelGetException.CANCELLED:
			onFailure(new FetchException(FetchException.CANCELLED), sched);
			return;
		default:
			Logger.error(this, "Unknown LowLevelGetException code: "+e.code);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR), sched);
			return;
		}
	}

	final void onFailure(FetchException e, RequestScheduler sched) {
		onFailure(e, false, sched);
	}
	
	// Real onFailure
	protected void onFailure(FetchException e, boolean forceFatal, RequestScheduler sched) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "onFailure( "+e+" , "+forceFatal+")", e);
		if(parent.isCancelled() || cancelled) {
			if(logMINOR) Logger.minor(this, "Failing: cancelled");
			e = new FetchException(FetchException.CANCELLED);
			forceFatal = true;
		}
		if(!(e.isFatal() || forceFatal) ) {
			if(retry(sched, ctx.executor)) {
				if(logMINOR) Logger.minor(this, "Retrying");
				return;
			}
		}
		// :(
		unregister(false);
		if(e.isFatal() || forceFatal)
			parent.fatallyFailedBlock();
		else
			parent.failedBlock();
		rcb.onFailure(e, this);
	}

	/** Will be overridden by SingleFileFetcher */
	protected void onSuccess(FetchResult data, RequestScheduler sched) {
		unregister(false);
		if(parent.isCancelled()) {
			data.asBucket().free();
			onFailure(new FetchException(FetchException.CANCELLED), sched);
			return;
		}
		rcb.onSuccess(data, this);
	}

	public void onSuccess(ClientKeyBlock block, boolean fromStore, Object reqTokenIgnored, RequestScheduler sched) {
		if(parent instanceof ClientGetter)
			((ClientGetter)parent).addKeyToBinaryBlob(block);
		Bucket data = extract(block, sched);
		if(data == null) return; // failed
		if(!block.isMetadata()) {
			onSuccess(new FetchResult((ClientMetadata)null, data), sched);
		} else {
			onFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), sched);
		}
	}

	/** Convert a ClientKeyBlock to a Bucket. If an error occurs, report it via onFailure
	 * and return null.
	 */
	protected Bucket extract(ClientKeyBlock block, RequestScheduler sched) {
		Bucket data;
		try {
			data = block.decode(ctx.bucketFactory, (int)(Math.min(ctx.maxOutputLength, Integer.MAX_VALUE)), false);
		} catch (KeyDecodeException e1) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Decode failure: "+e1, e1);
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage()), sched);
			return null;
		} catch (TooBigException e) {
			onFailure(new FetchException(FetchException.TOO_BIG, e), sched);
			return null;
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			onFailure(new FetchException(FetchException.BUCKET_ERROR, e), sched);
			return null;
		}
		return data;
	}

	/** getToken() is not supported */
	public long getToken() {
		return token;
	}

}
