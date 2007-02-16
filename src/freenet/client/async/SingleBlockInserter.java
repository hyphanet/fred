/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;

import freenet.client.FailureCodeTracker;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.keys.SSKEncodeException;
import freenet.node.LowLevelPutException;
import freenet.node.NodeClientCore;
import freenet.node.SendableInsert;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Insert *ONE KEY*.
 */
public class SingleBlockInserter implements SendableInsert, ClientPutState {

	private static boolean logMINOR;
	final Bucket sourceData;
	final short compressionCodec;
	final FreenetURI uri; // uses essentially no RAM in the common case of a CHK because we use FreenetURI.EMPTY_CHK_URI
	FreenetURI resultingURI;
	final PutCompletionCallback cb;
	final BaseClientPutter parent;
	final InserterContext ctx;
	private int retries;
	private final FailureCodeTracker errors;
	private boolean finished;
	private final boolean dontSendEncoded;
	private WeakReference refToClientKeyBlock;
	final int token; // for e.g. splitfiles
	private final Object tokenObject;
	final boolean isMetadata;
	final boolean getCHKOnly;
	final int sourceLength;
	private int consecutiveRNFs;
	
	public SingleBlockInserter(BaseClientPutter parent, Bucket data, short compressionCodec, FreenetURI uri, InserterContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, boolean getCHKOnly, boolean addToParent, boolean dontSendEncoded, Object tokenObject) {
		this.consecutiveRNFs = 0;
		this.tokenObject = tokenObject;
		this.token = token;
		this.parent = parent;
		this.dontSendEncoded = dontSendEncoded;
		this.retries = 0;
		this.finished = false;
		this.ctx = ctx;
		errors = new FailureCodeTracker(true);
		this.cb = cb;
		this.uri = uri;
		this.compressionCodec = compressionCodec;
		this.sourceData = data;
		this.isMetadata = isMetadata;
		this.sourceLength = sourceLength;
		this.getCHKOnly = getCHKOnly;
		if(addToParent) {
			parent.addBlock();
			parent.addMustSucceedBlocks(1);
			parent.notifyClients();
		}
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	protected ClientKeyBlock innerEncode() throws InserterException {
		String uriType = uri.getKeyType().toUpperCase();
		if(uriType.equals("CHK")) {
			try {
				return ClientCHKBlock.encode(sourceData, isMetadata, compressionCodec == -1, compressionCodec, sourceLength);
			} catch (CHKEncodeException e) {
				Logger.error(this, "Caught "+e, e);
				throw new InserterException(InserterException.INTERNAL_ERROR, e, null);
			} catch (IOException e) {
				Logger.error(this, "Caught "+e, e);
				throw new InserterException(InserterException.BUCKET_ERROR, e, null);
			}
		} else if(uriType.equals("SSK") || uriType.equals("KSK")) {
			try {
				InsertableClientSSK ik = InsertableClientSSK.create(uri);
				return ik.encode(sourceData, isMetadata, compressionCodec == -1, compressionCodec, sourceLength, ctx.random);
			} catch (MalformedURLException e) {
				throw new InserterException(InserterException.INVALID_URI, e, null);
			} catch (SSKEncodeException e) {
				Logger.error(this, "Caught "+e, e);
				throw new InserterException(InserterException.INTERNAL_ERROR, e, null);
			} catch (IOException e) {
				Logger.error(this, "Caught "+e, e);
				throw new InserterException(InserterException.BUCKET_ERROR, e, null);
			}
		} else {
			throw new InserterException(InserterException.INVALID_URI, "Unknown keytype "+uriType, null);
		}
	}

	protected ClientKeyBlock encode() throws InserterException {
		ClientKeyBlock block;
		boolean shouldSend;
		synchronized(this) {
			if(refToClientKeyBlock != null) {
				block = (ClientKeyBlock) refToClientKeyBlock.get();
				if(block != null) return block;
			}
			block = innerEncode();
			refToClientKeyBlock = 
				new WeakReference(block);
			shouldSend = (resultingURI == null);
			resultingURI = block.getClientKey().getURI();
		}
		if(shouldSend && !dontSendEncoded)
			cb.onEncode(block.getClientKey(), this);
		return block;
	}
	
	public boolean isInsert() {
		return true;
	}

	public short getPriorityClass() {
		return parent.getPriorityClass();
	}

	public int getRetryCount() {
		return retries;
	}

	public void onFailure(LowLevelPutException e) {
		if(parent.isCancelled()) {
			fail(new InserterException(InserterException.CANCELLED));
			return;
		}
		
		switch(e.code) {
		case LowLevelPutException.COLLISION:
			fail(new InserterException(InserterException.COLLISION));
			break;
		case LowLevelPutException.INTERNAL_ERROR:
			errors.inc(InserterException.INTERNAL_ERROR);
			break;
		case LowLevelPutException.REJECTED_OVERLOAD:
			errors.inc(InserterException.REJECTED_OVERLOAD);
			break;
		case LowLevelPutException.ROUTE_NOT_FOUND:
			errors.inc(InserterException.ROUTE_NOT_FOUND);
			break;
		case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
			errors.inc(InserterException.ROUTE_REALLY_NOT_FOUND);
			break;
		default:
			Logger.error(this, "Unknown LowLevelPutException code: "+e.code);
			errors.inc(InserterException.INTERNAL_ERROR);
		}
		if(e.code == LowLevelPutException.ROUTE_NOT_FOUND) {
			consecutiveRNFs++;
			if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+consecutiveRNFs+" / "+ctx.consecutiveRNFsCountAsSuccess);
			if(consecutiveRNFs == ctx.consecutiveRNFsCountAsSuccess) {
				if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+consecutiveRNFs+" - counting as success");
				onSuccess();
				return;
			}
		} else
			consecutiveRNFs = 0;
		if(logMINOR) Logger.minor(this, "Failed: "+e);
		retries++;
		if((retries > ctx.maxInsertRetries) && (ctx.maxInsertRetries != -1)) {
			if(errors.isOneCodeOnly())
				fail(new InserterException(errors.getFirstCode()));
			else
				fail(new InserterException(InserterException.TOO_MANY_RETRIES_IN_BLOCKS, errors, getURI()));
			return;
		}
		getScheduler().register(this);
	}

	private void fail(InserterException e) {
		fail(e, false);
	}
	
	private void fail(InserterException e, boolean forceFatal) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(e.isFatal() || forceFatal)
			parent.fatallyFailedBlock();
		else
			parent.failedBlock();
		cb.onFailure(e, this);
	}

	public ClientKeyBlock getBlock() {
		try {
			synchronized (this) {
				if(finished) return null;
			}
			return encode();				
		} catch (InserterException e) {
			cb.onFailure(e, this);
			return null;
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			cb.onFailure(new InserterException(InserterException.INTERNAL_ERROR, t, null), this);
			return null;
		}
	}

	public void schedule() throws InserterException {
		synchronized(this) {
			if(finished) return;
		}
		if(getCHKOnly) {
			ClientKeyBlock block = encode();
			cb.onEncode(block.getClientKey(), this);
			cb.onSuccess(this);
			parent.completedBlock(false);
			finished = true;
		} else {
			getScheduler().register(this);
		}
	}

	private ClientRequestScheduler getScheduler() {
		String uriType = uri.getKeyType().toUpperCase();
		if(uriType.equals("CHK"))
			return parent.chkScheduler;
		else if(uriType.equals("SSK") || uriType.equals("KSK"))
			return parent.sskScheduler;
		else throw new IllegalArgumentException();
	}

	public FreenetURI getURI() {
		synchronized(this) {
			if(resultingURI != null)
				return resultingURI;
		}
		getBlock();
		synchronized(this) {
			// FIXME not really necessary? resultingURI is never dropped, only set.
			return resultingURI;
		}
	}

	public synchronized FreenetURI getURINoEncode() {
		return resultingURI;
	}

	public void onSuccess() {
		if(logMINOR) Logger.minor(this, "Succeeded ("+this+"): "+token);
		if(parent.isCancelled()) {
			fail(new InserterException(InserterException.CANCELLED));
			return;
		}
		synchronized(this) {
			finished = true;
		}
		parent.completedBlock(false);
		cb.onSuccess(this);
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void cancel() {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		cb.onFailure(new InserterException(InserterException.CANCELLED), this);
	}

	public synchronized boolean isFinished() {
		return finished;
	}

	public void send(NodeClientCore core) {
		try {
			if(logMINOR) Logger.minor(this, "Starting request: "+this);
			ClientKeyBlock b = getBlock();
			if(b != null)
				core.realPut(b, ctx.cacheLocalRequests);
			else
				fail(new InserterException(InserterException.CANCELLED));
		} catch (LowLevelPutException e) {
			onFailure(e);
			if(logMINOR) Logger.minor(this, "Request failed: "+this+" for "+e);
			return;
		}
		if(logMINOR) Logger.minor(this, "Request succeeded: "+this);
		onSuccess();
	}

	public Object getClient() {
		return parent.getClient();
	}

	public ClientRequester getClientRequest() {
		return parent;
	}

	public Object getToken() {
		return tokenObject;
	}

	public SimpleFieldSet getProgressFieldset() {
		return null;
	}

	/** Attempt to encode the block, if necessary */
	public void tryEncode() {
		try {
			encode();
		} catch (InserterException e) {
			fail(e);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			// Don't requeue on BackgroundBlockEncoder.
			// Not necessary to do so (we'll ask again when we need it), and it'll probably just break again.
		}
	}

	public boolean canRemove() {
		return true;
	}

}
