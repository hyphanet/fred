/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.keys.TooBigException;
import freenet.node.LowLevelGetException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

/** 
 * Fetch a single block file.
 * Used by SplitFileFetcherSegment.
 */
public class SimpleSingleFileFetcher extends BaseSingleFileFetcher implements ClientGetState {

	SimpleSingleFileFetcher(ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent, 
			GetCompletionCallback rcb, boolean isEssential, boolean dontAdd, long l, ObjectContainer container, ClientContext context, boolean deleteFetchContext, boolean realTimeFlag) {
		super(key, maxRetries, ctx, parent, deleteFetchContext, realTimeFlag);
		this.rcb = rcb;
		this.token = l;
		if(!dontAdd) {
			if(isEssential)
				parent.addMustSucceedBlocks(1, container);
			else
				parent.addBlock(container);
			parent.notifyClients(container, context);
		}
	}

	final GetCompletionCallback rcb;
	final long token;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	// Translate it, then call the real onFailure
	@Override
	public void onFailure(LowLevelGetException e, Object reqTokenIgnored, ObjectContainer container, ClientContext context) {
		switch(e.code) {
		case LowLevelGetException.DATA_NOT_FOUND:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), false, container, context);
			return;
		case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND), false, container, context);
			return;
		case LowLevelGetException.RECENTLY_FAILED:
			onFailure(new FetchException(FetchException.RECENTLY_FAILED), false, container, context);
			return;
		case LowLevelGetException.DECODE_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR), false, container, context);
			return;
		case LowLevelGetException.INTERNAL_ERROR:
			onFailure(new FetchException(FetchException.INTERNAL_ERROR), false, container, context);
			return;
		case LowLevelGetException.REJECTED_OVERLOAD:
			onFailure(new FetchException(FetchException.REJECTED_OVERLOAD), false, container, context);
			return;
		case LowLevelGetException.ROUTE_NOT_FOUND:
			onFailure(new FetchException(FetchException.ROUTE_NOT_FOUND), false, container, context);
			return;
		case LowLevelGetException.TRANSFER_FAILED:
			onFailure(new FetchException(FetchException.TRANSFER_FAILED), false, container, context);
			return;
		case LowLevelGetException.VERIFY_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR), false, container, context);
			return;
		case LowLevelGetException.CANCELLED:
			onFailure(new FetchException(FetchException.CANCELLED), false, container, context);
			return;
		default:
			Logger.error(this, "Unknown LowLevelGetException code: "+e.code);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR), false, container, context);
			return;
		}
	}

	// Real onFailure
	protected void onFailure(FetchException e, boolean forceFatal, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(rcb, 1);
		}
		if(logMINOR) Logger.minor(this, "onFailure( "+e+" , "+forceFatal+")", e);
		if(parent.isCancelled() || cancelled) {
			if(logMINOR) Logger.minor(this, "Failing: cancelled");
			e = new FetchException(FetchException.CANCELLED);
			forceFatal = true;
		}
		if(!(e.isFatal() || forceFatal) ) {
			if(retry(container, context)) {
				if(logMINOR) Logger.minor(this, "Retrying");
				return;
			}
		}
		// :(
		unregisterAll(container, context);
		synchronized(this) {
			finished = true;
		}
		if(persistent)
			container.store(this);
		if(e.isFatal() || forceFatal)
			parent.fatallyFailedBlock(container, context);
		else
			parent.failedBlock(container, context);
		rcb.onFailure(e, this, container, context);
	}

	/** Will be overridden by SingleFileFetcher */
	protected void onSuccess(FetchResult data, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(rcb, 1);
		}
		if(parent.isCancelled()) {
			data.asBucket().free();
			if(persistent) data.asBucket().removeFrom(container);
			onFailure(new FetchException(FetchException.CANCELLED), false, container, context);
			return;
		}
		rcb.onSuccess(new SingleFileStreamGenerator(data.asBucket(), persistent), data.getMetadata(), null, this, container, context);
	}

	@Override
	public void onSuccess(ClientKeyBlock block, boolean fromStore, Object reqTokenIgnored, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(parent, 1);
		}
		if(parent instanceof ClientGetter)
			((ClientGetter)parent).addKeyToBinaryBlob(block, container, context);
		Bucket data = extract(block, container, context);
		if(data == null) return; // failed
		context.uskManager.checkUSK(key.getURI(), fromStore, container, block.isMetadata());
		if(!block.isMetadata()) {
			onSuccess(new FetchResult(new ClientMetadata(null), data), container, context);
		} else {
			onFailure(new FetchException(FetchException.INVALID_METADATA, "Metadata where expected data"), false, container, context);
		}
	}

	/** Convert a ClientKeyBlock to a Bucket. If an error occurs, report it via onFailure
	 * and return null.
	 */
	protected Bucket extract(ClientKeyBlock block, ObjectContainer container, ClientContext context) {
		Bucket data;
		try {
			data = block.decode(context.getBucketFactory(parent.persistent()), (int)(Math.min(ctx.maxOutputLength, Integer.MAX_VALUE)), false);
		} catch (KeyDecodeException e1) {
			if(logMINOR)
				Logger.minor(this, "Decode failure: "+e1, e1);
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage()), false, container, context);
			return null;
		} catch (TooBigException e) {
			onFailure(new FetchException(FetchException.TOO_BIG, e), false, container, context);
			return null;
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			onFailure(new FetchException(FetchException.BUCKET_ERROR, e), false, container, context);
			return null;
		}
		return data;
	}

	/** getToken() is not supported */
	@Override
	public long getToken() {
		return token;
	}

	@Override
	public void onFailed(KeyListenerConstructionException e, ObjectContainer container, ClientContext context) {
		onFailure(e.getFetchException(), false, container, context);
	}

	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		super.removeFrom(container, context);
		// rcb is definitely not our responsibility.
	}
	
	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		super.cancel(container, context);
		if(persistent) container.activate(rcb, 1);
		rcb.onFailure(new FetchException(FetchException.CANCELLED), this, container, context);
	}

	@Override
	protected void notFoundInStore(ObjectContainer container,
			ClientContext context) {
		this.onFailure(new FetchException(FetchException.DATA_NOT_FOUND), true, container, context);
	}

	@Override
	protected void onBlockDecodeError(Object token, ObjectContainer container,
			ClientContext context) {
		onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, "Could not decode block with the URI given, probably invalid as inserted, possible the URI is wrong"), true, container, context);
	}

}
