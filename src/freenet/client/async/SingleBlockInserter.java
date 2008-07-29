/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.crypt.RandomSource;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.keys.SSKEncodeException;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelPutException;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableInsert;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.NativeThread;

/**
 * Insert *ONE KEY*.
 */
public class SingleBlockInserter extends SendableInsert implements ClientPutState {

	private static boolean logMINOR;
	final Bucket sourceData;
	final short compressionCodec;
	final FreenetURI uri; // uses essentially no RAM in the common case of a CHK because we use FreenetURI.EMPTY_CHK_URI
	FreenetURI resultingURI;
	final PutCompletionCallback cb;
	final BaseClientPutter parent;
	final InsertContext ctx;
	private int retries;
	private final FailureCodeTracker errors;
	private boolean finished;
	private final boolean dontSendEncoded;
	private transient SoftReference refToClientKeyBlock;
	final int token; // for e.g. splitfiles
	private final Object tokenObject;
	final boolean isMetadata;
	final boolean getCHKOnly;
	final int sourceLength;
	private int consecutiveRNFs;
	
	public SingleBlockInserter(BaseClientPutter parent, Bucket data, short compressionCodec, FreenetURI uri, InsertContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, boolean getCHKOnly, boolean addToParent, boolean dontSendEncoded, Object tokenObject, ObjectContainer container, ClientContext context, boolean persistent) {
		super(parent.persistent());
		assert(persistent == parent.persistent());
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
		if(sourceData == null) throw new NullPointerException();
		this.isMetadata = isMetadata;
		this.sourceLength = sourceLength;
		this.getCHKOnly = getCHKOnly;
		if(addToParent) {
			parent.addBlock(container);
			parent.addMustSucceedBlocks(1, container);
			parent.notifyClients(container, context);
		}
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	protected ClientKeyBlock innerEncode(RandomSource random, ObjectContainer container) throws InsertException {
		if(persistent)
			container.activate(uri, 1);
		String uriType = uri.getKeyType();
		if(uriType.equals("CHK")) {
			try {
				return ClientCHKBlock.encode(sourceData, isMetadata, compressionCodec == -1, compressionCodec, sourceLength);
			} catch (CHKEncodeException e) {
				Logger.error(this, "Caught "+e, e);
				throw new InsertException(InsertException.INTERNAL_ERROR, e, null);
			} catch (IOException e) {
				Logger.error(this, "Caught "+e+" encoding data "+sourceData, e);
				throw new InsertException(InsertException.BUCKET_ERROR, e, null);
			}
		} else if(uriType.equals("SSK") || uriType.equals("KSK")) {
			try {
				InsertableClientSSK ik = InsertableClientSSK.create(uri);
				return ik.encode(sourceData, isMetadata, compressionCodec == -1, compressionCodec, sourceLength, random);
			} catch (MalformedURLException e) {
				throw new InsertException(InsertException.INVALID_URI, e, null);
			} catch (SSKEncodeException e) {
				Logger.error(this, "Caught "+e, e);
				throw new InsertException(InsertException.INTERNAL_ERROR, e, null);
			} catch (IOException e) {
				Logger.error(this, "Caught "+e, e);
				throw new InsertException(InsertException.BUCKET_ERROR, e, null);
			}
		} else {
			throw new InsertException(InsertException.INVALID_URI, "Unknown keytype "+uriType, null);
		}
	}

	protected ClientKeyBlock encode(ObjectContainer container, ClientContext context, boolean calledByCB) throws InsertException {
		if(persistent) {
			container.activate(sourceData, 1);
			container.activate(cb, 1);
		}
		ClientKeyBlock block;
		boolean shouldSend;
		synchronized(this) {
			if(refToClientKeyBlock != null) {
				block = (ClientKeyBlock) refToClientKeyBlock.get();
				if(block != null) return block;
			}
			block = innerEncode(context.random, container);
			refToClientKeyBlock = 
				new SoftReference(block);
			shouldSend = (resultingURI == null);
			resultingURI = block.getClientKey().getURI();
		}
		if(shouldSend && !dontSendEncoded)
			cb.onEncode(block.getClientKey(), this, container, context);
		if(shouldSend && persistent)
			container.set(this);
		if(persistent && !calledByCB)
			container.deactivate(cb, 1);
		return block;
	}
	
	public boolean isInsert() {
		return true;
	}

	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(parent, 1);
		return parent.getPriorityClass(); // Not much point deactivating
	}

	public int getRetryCount() {
		return retries;
	}

	public void onFailure(LowLevelPutException e, Object keyNum, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(errors, 1);
		if(parent.isCancelled()) {
			fail(new InsertException(InsertException.CANCELLED), container, context);
			return;
		}
		
		switch(e.code) {
		case LowLevelPutException.COLLISION:
			fail(new InsertException(InsertException.COLLISION), container, context);
			break;
		case LowLevelPutException.INTERNAL_ERROR:
			errors.inc(InsertException.INTERNAL_ERROR);
			break;
		case LowLevelPutException.REJECTED_OVERLOAD:
			errors.inc(InsertException.REJECTED_OVERLOAD);
			break;
		case LowLevelPutException.ROUTE_NOT_FOUND:
			errors.inc(InsertException.ROUTE_NOT_FOUND);
			break;
		case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
			errors.inc(InsertException.ROUTE_REALLY_NOT_FOUND);
			break;
		default:
			Logger.error(this, "Unknown LowLevelPutException code: "+e.code);
			errors.inc(InsertException.INTERNAL_ERROR);
		}
		if(e.code == LowLevelPutException.ROUTE_NOT_FOUND || e.code == LowLevelPutException.ROUTE_REALLY_NOT_FOUND) {
			consecutiveRNFs++;
			if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+consecutiveRNFs+" / "+ctx.consecutiveRNFsCountAsSuccess);
			if(consecutiveRNFs == ctx.consecutiveRNFsCountAsSuccess) {
				if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+consecutiveRNFs+" - counting as success");
				onSuccess(keyNum, container, context);
				return;
			}
		} else
			consecutiveRNFs = 0;
		if(logMINOR) Logger.minor(this, "Failed: "+e);
		retries++;
		if((retries > ctx.maxInsertRetries) && (ctx.maxInsertRetries != -1)) {
			fail(InsertException.construct(errors), container, context);
			return;
		}
		if(persistent)
			container.set(this);
		getScheduler(context).registerInsert(this, persistent, false, true);
	}

	private void fail(InsertException e, ObjectContainer container, ClientContext context) {
		fail(e, false, container, context);
	}
	
	private void fail(InsertException e, boolean forceFatal, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(persistent)
			container.set(this);
		if(e.isFatal() || forceFatal)
			parent.fatallyFailedBlock(container, context);
		else
			parent.failedBlock(container, context);
		if(persistent)
			container.activate(cb, 1);
		cb.onFailure(e, this, container, context);
	}

	public ClientKeyBlock getBlock(ObjectContainer container, ClientContext context, boolean calledByCB) {
		try {
			synchronized (this) {
				if(finished) return null;
			}
			if(persistent)
				container.set(this);
			return encode(container, context, calledByCB);
		} catch (InsertException e) {
			if(persistent)
				container.activate(cb, 1);
			cb.onFailure(e, this, container, context);
			if(!calledByCB)
				container.deactivate(cb, 1);
			return null;
		} catch (Throwable t) {
			if(persistent)
				container.activate(cb, 1);
			Logger.error(this, "Caught "+t, t);
			cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR, t, null), this, container, context);
			if(!calledByCB)
				container.deactivate(cb, 1);
			return null;
		}
	}

	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		synchronized(this) {
			if(finished) {
				if(logMINOR)
					Logger.minor(this, "Finished already: "+this);
				return;
			}
		}
		if(getCHKOnly) {
			if(persistent)
				container.activate(cb, 1);
			ClientKeyBlock block = encode(container, context, true);
			cb.onEncode(block.getClientKey(), this, container, context);
			parent.completedBlock(false, container, context);
			cb.onSuccess(this, container, context);
			finished = true;
			if(persistent)
				container.set(this);
		} else {
			getScheduler(context).registerInsert(this, persistent, true, true);
		}
	}

	public boolean isSSK() {
		return uri.getKeyType().toUpperCase().equals("SSK");
	}

	public FreenetURI getURI(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(resultingURI != null)
				return resultingURI;
		}
		getBlock(container, context, true);
		synchronized(this) {
			// FIXME not really necessary? resultingURI is never dropped, only set.
			return resultingURI;
		}
	}

	public synchronized FreenetURI getURINoEncode() {
		return resultingURI;
	}

	public void onSuccess(Object keyNum, ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Succeeded ("+this+"): "+token);
		if(persistent)
			container.activate(parent, 1);
		if(parent.isCancelled()) {
			fail(new InsertException(InsertException.CANCELLED), container, context);
			return;
		}
		synchronized(this) {
			if(finished) {
				// Normal with persistence.
				Logger.normal(this, "Block already completed: "+this);
				return;
			}
			finished = true;
		}
		if(persistent) {
			container.activate(cb, 1);
			container.set(this);
		}
		parent.completedBlock(false, container, context);
		cb.onSuccess(this, container, context);
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(persistent) {
			container.set(this);
			container.activate(cb, 1);
		}
		super.unregister(container, context);
		cb.onFailure(new InsertException(InsertException.CANCELLED), this, container, context);
	}

	public synchronized boolean isEmpty(ObjectContainer container) {
		return finished;
	}
	
	public synchronized boolean isCancelled(ObjectContainer container) {
		return finished;
	}
	
	public boolean send(NodeClientCore core, RequestScheduler sched, ChosenRequest req) {
		// Ignore keyNum, key, since we're only sending one block.
		try {
			if(logMINOR) Logger.minor(this, "Starting request: "+this);
			ClientKeyBlock b = (ClientKeyBlock) req.token;
			if(b != null)
				core.realPut(b, req.cacheLocalRequests);
			else {
				Logger.error(this, "Asked to send empty block on "+this, new Exception("error"));
				return false;
			}
		} catch (LowLevelPutException e) {
			sched.callFailure((SendableInsert) this, e, req.token, NativeThread.NORM_PRIORITY, req, req.isPersistent());
			if(logMINOR) Logger.minor(this, "Request failed: "+this+" for "+e);
			return true;
		}
		if(logMINOR) Logger.minor(this, "Request succeeded: "+this);
		sched.callSuccess(this, req.token, NativeThread.NORM_PRIORITY, req, req.isPersistent());
		return true;
	}

	public RequestClient getClient() {
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
	public void tryEncode(ObjectContainer container, ClientContext context) {
		try {
			encode(container, context, false);
		} catch (InsertException e) {
			fail(e, container, context);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			// Don't requeue on BackgroundBlockEncoder.
			// Not necessary to do so (we'll ask again when we need it), and it'll probably just break again.
		}
	}

	public boolean canRemove(ObjectContainer container) {
		return true;
	}

	public synchronized Object[] sendableKeys(ObjectContainer container) {
		if(finished)
			return new Object[] {};
		else
			return new Object[] { new Integer(0) };
	}

	public synchronized Object[] allKeys(ObjectContainer container) {
		return sendableKeys(container);
	}

	public synchronized Object chooseKey(KeysFetchingLocally ignored, ObjectContainer container, ClientContext context) {
		if(finished) return null;
		// Ignore KeysFetchingLocally, it's for requests.
		return getBlock(container, context, false);
	}

}
