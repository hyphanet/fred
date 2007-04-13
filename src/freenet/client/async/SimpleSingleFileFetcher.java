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
import freenet.support.Logger;
import freenet.support.api.Bucket;

/** 
 * Fetch a single block file.
 * Used by SplitFileFetcherSegment.
 */
public class SimpleSingleFileFetcher extends BaseSingleFileFetcher implements ClientGetState {

	SimpleSingleFileFetcher(ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent, 
			GetCompletionCallback rcb, boolean isEssential, long l) {
		super(key, maxRetries, ctx, parent);
		this.rcb = rcb;
		this.token = l;
		parent.addBlock();
		if(isEssential)
			parent.addMustSucceedBlocks(1);
	}

	final GetCompletionCallback rcb;
	final long token;
	
	// Translate it, then call the real onFailure
	public void onFailure(LowLevelGetException e, int reqTokenIgnored) {
		switch(e.code) {
		case LowLevelGetException.DATA_NOT_FOUND:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND));
			return;
		case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND));
			return;
		case LowLevelGetException.DECODE_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR));
			return;
		case LowLevelGetException.INTERNAL_ERROR:
			onFailure(new FetchException(FetchException.INTERNAL_ERROR));
			return;
		case LowLevelGetException.REJECTED_OVERLOAD:
			onFailure(new FetchException(FetchException.REJECTED_OVERLOAD));
			return;
		case LowLevelGetException.ROUTE_NOT_FOUND:
			onFailure(new FetchException(FetchException.ROUTE_NOT_FOUND));
			return;
		case LowLevelGetException.TRANSFER_FAILED:
			onFailure(new FetchException(FetchException.TRANSFER_FAILED));
			return;
		case LowLevelGetException.VERIFY_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR));
			return;
		case LowLevelGetException.CANCELLED:
			onFailure(new FetchException(FetchException.CANCELLED));
			return;
		default:
			Logger.error(this, "Unknown LowLevelGetException code: "+e.code);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR));
			return;
		}
	}

	final void onFailure(FetchException e) {
		onFailure(e, false);
	}
	
	// Real onFailure
	protected void onFailure(FetchException e, boolean forceFatal) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "onFailure( "+e+" , "+forceFatal+")", e);
		if(parent.isCancelled() || cancelled) {
			if(logMINOR) Logger.minor(this, "Failing: cancelled");
			e = new FetchException(FetchException.CANCELLED);
			forceFatal = true;
		}
		if(!(e.isFatal() || forceFatal) ) {
			if(retry()) {
				if(logMINOR) Logger.minor(this, "Retrying");
				return;
			}
		}
		// :(
		if(e.isFatal() || forceFatal)
			parent.fatallyFailedBlock();
		else
			parent.failedBlock();
		rcb.onFailure(e, this);
	}

	/** Will be overridden by SingleFileFetcher */
	protected void onSuccess(FetchResult data) {
		if(parent.isCancelled()) {
			data.asBucket().free();
			onFailure(new FetchException(FetchException.CANCELLED));
			return;
		}
		rcb.onSuccess(data, this);
	}

	public void onSuccess(ClientKeyBlock block, boolean fromStore, int reqTokenIgnored) {
		Bucket data = extract(block);
		if(data == null) return; // failed
		if(!block.isMetadata()) {
			onSuccess(new FetchResult((ClientMetadata)null, data));
		} else {
			onFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"));
		}
	}

	/** Convert a ClientKeyBlock to a Bucket. If an error occurs, report it via onFailure
	 * and return null.
	 */
	protected Bucket extract(ClientKeyBlock block) {
		Bucket data;
		try {
			data = block.decode(ctx.bucketFactory, (int)(Math.min(ctx.maxOutputLength, Integer.MAX_VALUE)), false);
		} catch (KeyDecodeException e1) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Decode failure: "+e1, e1);
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage()));
			return null;
		} catch (TooBigException e) {
			onFailure(new FetchException(FetchException.TOO_BIG, e));
			return null;
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			onFailure(new FetchException(FetchException.BUCKET_ERROR, e));
			return null;
		}
		return data;
	}

	/** getToken() is not supported */
	public long getToken() {
		return token;
	}

}
