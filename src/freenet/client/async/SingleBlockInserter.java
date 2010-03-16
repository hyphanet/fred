/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.crypt.RandomSource;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.ClientSSKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.keys.KeyDecodeException;
import freenet.keys.KeyEncodeException;
import freenet.keys.KeyVerifyException;
import freenet.keys.SSKEncodeException;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelPutException;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableInsert;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestSender;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.io.BucketTools;
import freenet.support.io.NativeThread;

/**
 * Insert *ONE KEY*.
 */
public class SingleBlockInserter extends SendableInsert implements ClientPutState, Encodeable {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
				logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
			}
		});
	}
	
	Bucket sourceData;
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
	final int token; // for e.g. splitfiles
	private final Object tokenObject;
	final boolean isMetadata;
	final boolean getCHKOnly;
	final int sourceLength;
	private int consecutiveRNFs;
	private boolean isSSK;
	private boolean freeData;
	private int completedInserts;
	final int extraInserts;
	
	/**
	 * Create a SingleBlockInserter.
	 * @param parent
	 * @param data
	 * @param compressionCodec The compression codec.
	 * @param uri
	 * @param ctx
	 * @param cb
	 * @param isMetadata
	 * @param sourceLength The length of the original, uncompressed data.
	 * @param token
	 * @param getCHKOnly
	 * @param addToParent
	 * @param dontSendEncoded
	 * @param tokenObject
	 * @param container
	 * @param context
	 * @param persistent
	 * @param freeData
	 */
	public SingleBlockInserter(BaseClientPutter parent, Bucket data, short compressionCodec, FreenetURI uri, InsertContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, boolean getCHKOnly, boolean addToParent, boolean dontSendEncoded, Object tokenObject, ObjectContainer container, ClientContext context, boolean persistent, boolean freeData, int extraInserts) {
		super(persistent);
		assert(persistent == parent.persistent());
		this.consecutiveRNFs = 0;
		this.tokenObject = tokenObject;
		this.token = token;
		this.parent = parent;
		this.dontSendEncoded = dontSendEncoded;
		this.retries = 0;
		this.finished = false;
		this.ctx = ctx;
		this.freeData = freeData;
		errors = new FailureCodeTracker(true);
		this.cb = cb;
		this.uri = uri;
		this.compressionCodec = compressionCodec;
		this.sourceData = data;
		if(sourceData == null) throw new NullPointerException();
		this.isMetadata = isMetadata;
		this.sourceLength = sourceLength;
		this.getCHKOnly = getCHKOnly;
		isSSK = "SSK".equals(uri.getKeyType().toUpperCase());
		if(addToParent) {
			parent.addBlock(container);
			parent.addMustSucceedBlocks(1, container);
			parent.notifyClients(container, context);
		}
		this.extraInserts = extraInserts;
	}

	protected ClientKeyBlock innerEncode(RandomSource random, ObjectContainer container) throws InsertException {
		if(persistent) {
			container.activate(uri, 1);
			container.activate(sourceData, 1);
		}
		try {
			return innerEncode(random, uri, sourceData, isMetadata, compressionCodec, sourceLength, ctx.compressorDescriptor);
		} catch (KeyEncodeException e) {
			Logger.error(SingleBlockInserter.class, "Caught "+e, e);
			throw new InsertException(InsertException.INTERNAL_ERROR, e, null);
		} catch (MalformedURLException e) {
			throw new InsertException(InsertException.INVALID_URI, e, null);
		} catch (IOException e) {
			Logger.error(SingleBlockInserter.class, "Caught "+e+" encoding data "+sourceData, e);
			throw new InsertException(InsertException.BUCKET_ERROR, e, null);
		} catch (InvalidCompressionCodecException e) {
			throw new InsertException(InsertException.INTERNAL_ERROR, e, null);
		}
			
	}
	
	protected static ClientKeyBlock innerEncode(RandomSource random, FreenetURI uri, Bucket sourceData, boolean isMetadata, short compressionCodec, int sourceLength, String compressorDescriptor) throws InsertException, CHKEncodeException, IOException, SSKEncodeException, MalformedURLException, InvalidCompressionCodecException {
		String uriType = uri.getKeyType();
		if("CHK".equals(uriType)) {
			return ClientCHKBlock.encode(sourceData, isMetadata, compressionCodec == -1, compressionCodec, sourceLength, compressorDescriptor);
		} else if("SSK".equals(uriType) || "KSK".equals(uriType)) {
			InsertableClientSSK ik = InsertableClientSSK.create(uri);
			return ik.encode(sourceData, isMetadata, compressionCodec == -1, compressionCodec, sourceLength, random, compressorDescriptor);
		} else {
			throw new InsertException(InsertException.INVALID_URI, "Unknown keytype "+uriType, null);
		}
	}

	protected void onEncode(ClientKey key, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			if(resultingURI != null) return;
			resultingURI = key.getURI();
		}
		if(persistent) {
			container.store(this);
			container.activate(cb, 1);
		}
		cb.onEncode(key, this, container, context);
		if(persistent)
			container.deactivate(cb, 1);
	}
	
	protected ClientKeyBlock encode(ObjectContainer container, ClientContext context, boolean calledByCB) throws InsertException {
		if(persistent) {
			container.activate(sourceData, 1);
			container.activate(cb, 1);
		}
		ClientKeyBlock block;
		boolean shouldSend;
		synchronized(this) {
			if(finished) return null;
			if(sourceData == null) {
				Logger.error(this, "Source data is null on "+this+" but not finished!");
				return null;
			}
			block = innerEncode(context.random, container);
			shouldSend = (resultingURI == null);
			resultingURI = block.getClientKey().getURI();
		}
		if(logMINOR)
			Logger.minor(this, "Encoded "+resultingURI+" for "+this+" shouldSend="+shouldSend+" dontSendEncoded="+dontSendEncoded);
		if(shouldSend && !dontSendEncoded)
			cb.onEncode(block.getClientKey(), this, container, context);
		if(shouldSend && persistent)
			container.store(this);
		if(persistent && !calledByCB)
			container.deactivate(cb, 1);
		return block;
	}
	
	@Override
	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(parent, 1);
		return parent.getPriorityClass(); // Not much point deactivating
	}

	@Override
	public int getRetryCount() {
		return retries;
	}

	@Override
	public void onFailure(LowLevelPutException e, Object keyNum, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
		}
		if(persistent)
			container.activate(errors, 1);
		if(parent.isCancelled()) {
			fail(new InsertException(InsertException.CANCELLED), container, context);
			return;
		}
		if(logMINOR) Logger.minor(this, "onFailure() on "+e+" for "+this);
		
		switch(e.code) {
		case LowLevelPutException.COLLISION:
			fail(new InsertException(InsertException.COLLISION), container, context);
			return;
		case LowLevelPutException.INTERNAL_ERROR:
			fail(new InsertException(InsertException.INTERNAL_ERROR), container, context);
			return;
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
		if(persistent)
			container.activate(ctx, 1);
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
			fail(InsertException.construct(persistent ? errors.clone() : errors), container, context);
			if(persistent)
				container.deactivate(ctx, 1);
			return;
		}
		if(persistent) {
			container.store(this);
			container.deactivate(ctx, 1);
		}
		getScheduler(context).registerInsert(this, persistent, false, container);
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
			container.store(this);
		if(e.isFatal() || forceFatal)
			parent.fatallyFailedBlock(container, context);
		else
			parent.failedBlock(container, context);
		unregister(container, context, getPriorityClass(container));
		if(freeData) {
			if(persistent) container.activate(sourceData, 1);
			sourceData.free();
			if(persistent) sourceData.removeFrom(container);
			sourceData = null;
			if(persistent)
				container.store(this);
		}
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
				container.store(this);
			return encode(container, context, calledByCB);
		} catch (InsertException e) {
			if(persistent)
				container.activate(cb, 1);
			cb.onFailure(e, this, container, context);
			if(persistent && !calledByCB)
				container.deactivate(cb, 1);
			return null;
		} catch (Throwable t) {
			if(persistent)
				container.activate(cb, 1);
			Logger.error(this, "Caught "+t, t);
			cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR, t, null), this, container, context);
			if(persistent && !calledByCB)
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
			boolean deactivateCB = false;
			if(persistent) {
				deactivateCB = !container.ext().isActive(cb);
				if(deactivateCB)
					container.activate(cb, 1);
				container.activate(parent, 1);
			}
			ClientKeyBlock block = encode(container, context, true);
			cb.onEncode(block.getClientKey(), this, container, context);
			parent.completedBlock(false, container, context);
			cb.onSuccess(this, container, context);
			finished = true;
			if(persistent) {
				container.store(this);
				if(deactivateCB)
					container.deactivate(cb, 1);
			}
		} else {
			getScheduler(context).registerInsert(this, persistent, true, container);
		}
	}

	@Override
	public boolean isSSK() {
		return isSSK;
	}

	public FreenetURI getURI(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(resultingURI != null) {
				if(persistent) container.activate(resultingURI, 5);
				return persistent ? resultingURI.clone() : resultingURI;
			}
		}
		getBlock(container, context, true);
		synchronized(this) {
			// FIXME not really necessary? resultingURI is never dropped, only set.
			if(persistent) container.activate(resultingURI, 5);
			return persistent ? resultingURI.clone() : resultingURI;
		}
	}

	public synchronized FreenetURI getURINoEncode() {
		return resultingURI;
	}

	@Override
	public void onSuccess(Object keyNum, ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Succeeded ("+this+"): "+token);
		if(persistent)
			container.activate(parent, 1);
		if(parent.isCancelled()) {
			fail(new InsertException(InsertException.CANCELLED), container, context);
			return;
		}
		synchronized(this) {
			if(extraInserts > 0) {
				if(++completedInserts <= extraInserts) {
					if(logMINOR) Logger.minor(this, "Completed inserts "+completedInserts+" of extra inserts "+extraInserts+" on "+this);
					if(persistent) container.store(this);
					return; // Let it repeat until we've done enough inserts. It hasn't been unregistered yet.
				}
			}
			if(finished) {
				// Normal with persistence.
				Logger.normal(this, "Block already completed: "+this);
				return;
			}
			finished = true;
		}
		if(persistent) {
			container.store(this);
			container.activate(sourceData, 1);
		}
		if(freeData) {
			sourceData.free();
			if(persistent) sourceData.removeFrom(container);
			sourceData = null;
			if(persistent)
				container.store(this);
		}
		parent.completedBlock(false, container, context);
		unregister(container, context, getPriorityClass(container));
		if(persistent)
			container.activate(cb, 1);
		if(logMINOR) Logger.minor(this, "Calling onSuccess for "+cb);
		cb.onSuccess(this, container, context);
		if(persistent)
			container.deactivate(cb, 1);
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		boolean wasActive = true;
		if(persistent) {
			container.store(this);
			wasActive = container.ext().isActive(cb);
			if(!wasActive)
				container.activate(cb, 1);
			container.activate(sourceData, 1);
		}
		if(freeData) {
			sourceData.free();
			if(persistent) sourceData.removeFrom(container);
			sourceData = null;
			if(persistent)
				container.store(this);
		}
		super.unregister(container, context, getPriorityClass(container));
		cb.onFailure(new InsertException(InsertException.CANCELLED), this, container, context);
		if(!wasActive)
			container.deactivate(cb, 1);
	}

	public synchronized boolean isEmpty(ObjectContainer container) {
		return finished;
	}
	
	@Override
	public synchronized boolean isCancelled(ObjectContainer container) {
		return finished;
	}

	class MySendableRequestSender implements SendableRequestSender {

		final String compressorDescriptor;

		MySendableRequestSender() {
			compressorDescriptor = ctx.compressorDescriptor;
		}

		public boolean send(NodeClientCore core, RequestScheduler sched, final ClientContext context, ChosenBlock req) {
			// Ignore keyNum, key, since we're only sending one block.
			ClientKeyBlock b;
			ClientKey key = null;
			if(SingleBlockInserter.logMINOR) Logger.minor(this, "Starting request: "+SingleBlockInserter.this);
			BlockItem block = (BlockItem) req.token;
			try {
				try {
					b = innerEncode(context.random, block.uri, block.copyBucket, block.isMetadata, block.compressionCodec, block.sourceLength, compressorDescriptor);
				} catch (CHKEncodeException e) {
					throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage(), e);
				} catch (SSKEncodeException e) {
					throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage(), e);
				} catch (MalformedURLException e) {
					throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage(), e);
				} catch (InsertException e) {
					throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage(), e);
				} catch (IOException e) {
					throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage(), e);
				} catch (InvalidCompressionCodecException e) {
					throw new LowLevelPutException(LowLevelPutException.INTERNAL_ERROR, e.toString() + ":" + e.getMessage(), e);
				}
				if (b==null) {
					Logger.error(this, "Asked to send empty block on "+SingleBlockInserter.this, new Exception("error"));
					return false;
				}
				key = b.getClientKey();
				final ClientKey k = key;
				if(block.persistent) {
				context.jobRunner.queue(new DBJob() {

					public boolean run(ObjectContainer container, ClientContext context) {
						if(!container.ext().isStored(SingleBlockInserter.this)) return false;
						container.activate(SingleBlockInserter.this, 1);
						onEncode(k, container, context);
						container.deactivate(SingleBlockInserter.this, 1);
						return false;
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
				} else {
					context.mainExecutor.execute(new Runnable() {

						public void run() {
							onEncode(k, null, context);
						}
						
					}, "Got URI");
					
				}
				core.realPut(b, req.canWriteClientCache, req.forkOnCacheable);
			} catch (LowLevelPutException e) {
				if(e.code == LowLevelPutException.COLLISION) {
					// Collision
					try {
						ClientSSKBlock collided = (ClientSSKBlock) core.node.fetch((ClientSSK)key, true, true, req.canWriteClientCache);
						byte[] data = collided.memoryDecode(true);
						byte[] inserting = BucketTools.toByteArray(block.copyBucket);
						if(collided.isMetadata() == block.isMetadata && collided.getCompressionCodec() == block.compressionCodec && Arrays.equals(data, inserting)) {
							if(SingleBlockInserter.logMINOR) Logger.minor(this, "Collided with identical data: "+SingleBlockInserter.this);
							req.onInsertSuccess(context);
							return true;
						}
					} catch (KeyVerifyException e1) {
						Logger.error(this, "Caught "+e1+" when checking collision!", e1);
					} catch (KeyDecodeException e1) {
						Logger.error(this, "Caught "+e1+" when checking collision!", e1);
					} catch (IOException e1) {
						Logger.error(this, "Caught "+e1+" when checking collision!", e1);
					}
				}
				req.onFailure(e, context);
				if(SingleBlockInserter.logMINOR) Logger.minor(this, "Request failed: "+SingleBlockInserter.this+" for "+e);
				return true;
			} catch (DatabaseDisabledException e) {
				// Impossible, and nothing to do.
				Logger.error(this, "Running persistent insert but database is disabled!");
			} finally {
				block.copyBucket.free();
			}
			if(SingleBlockInserter.logMINOR) Logger.minor(this, "Request succeeded: "+SingleBlockInserter.this);
			req.onInsertSuccess(context);
			return true;
		}
	}

	
	@Override
	public SendableRequestSender getSender(ObjectContainer container, ClientContext context) {
		return new MySendableRequestSender();
	}

	@Override
	public RequestClient getClient(ObjectContainer container) {
		if(persistent) container.activate(parent, 1);
		return parent.getClient();
	}

	@Override
	public ClientRequester getClientRequest() {
		return parent;
	}

	public Object getToken() {
		return tokenObject;
	}

	/** Attempt to encode the block, if necessary */
	public void tryEncode(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(resultingURI != null) return;
			if(finished) return;
		}
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

	@Override
	public synchronized long countSendableKeys(ObjectContainer container, ClientContext context) {
		if(finished)
			return 0;
		else
			return 1;
	}

	@Override
	public synchronized long countAllKeys(ObjectContainer container, ClientContext context) {
		return countSendableKeys(container, context);
	}

	@Override
	public synchronized SendableRequestItem chooseKey(KeysFetchingLocally ignored, ObjectContainer container, ClientContext context) {
		if(finished) return null;
		if(!persistent) {
			if(ignored.hasTransientInsert(this, new FakeBlockItem()))
				return null;
		}
		return getBlockItem(container, context);
	}

	private BlockItem getBlockItem(ObjectContainer container, ClientContext context) {
		try {
			synchronized(this) {
				if(finished) return null;
			}
			if(persistent) {
				if(sourceData == null) {
					Logger.error(this, "getBlockItem(): sourceData = null but active = "+container.ext().isActive(this));
					return null;
				}
			}
			boolean deactivateBucket = false;
			if(persistent) {
				container.activate(uri, 1);
				deactivateBucket = !container.ext().isActive(sourceData);
				if(deactivateBucket)
					container.activate(sourceData, 1);
			}
			Bucket data = sourceData.createShadow();
			FreenetURI u = uri;
			if("CHK".equals(u.getKeyType()) && !persistent) u = FreenetURI.EMPTY_CHK_URI;
			else u = u.clone();
			if(data == null) {
				data = context.tempBucketFactory.makeBucket(sourceData.size());
				BucketTools.copy(sourceData, data);
			}
			if(persistent) {
				if(deactivateBucket)
					container.deactivate(sourceData, 1);
				container.deactivate(uri, 1);
			}
			return new BlockItem(this, data, isMetadata, compressionCodec, sourceLength, u, hashCode(), persistent);
		} catch (IOException e) {
			fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
			return null;
		}
	}
	
	@Override
	public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, ObjectContainer container, ClientContext context) {
		BlockItem item = getBlockItem(container, context);
		if(item == null) return null;
		PersistentChosenBlock block = new PersistentChosenBlock(true, request, item, null, null, sched);
		return Collections.singletonList(block);
	}

	private static class BlockItem implements SendableRequestItem {
		
		private final boolean persistent;
		private final Bucket copyBucket;
		private final boolean isMetadata;
		private final short compressionCodec;
		private final int sourceLength;
		private final FreenetURI uri;
		private final int hashCode;
		/** STRICTLY for purposes of equals() !!! */
		private final SingleBlockInserter parent;
		
		BlockItem(SingleBlockInserter parent, Bucket bucket, boolean meta, short codec, int srclen, FreenetURI u, int hashCode, boolean persistent) throws IOException {
			this.parent = parent;
			this.copyBucket = bucket;
			this.isMetadata = meta;
			this.compressionCodec = codec;
			this.sourceLength = srclen;
			this.uri = u;
			this.hashCode = hashCode;
			this.persistent = persistent;
		}
		
		public void dump() {
			copyBucket.free();
		}
		
		@Override
		public int hashCode() {
			return hashCode;
		}
		
		@Override
		public boolean equals(Object o) {
			if(o instanceof BlockItem) {
				if(((BlockItem)o).parent == parent) return true;
			} else if(o instanceof FakeBlockItem) {
				if(((FakeBlockItem)o).getParent() == parent) return true;
			}
			return false;
		}
		
	}
	
	// Used for testing whether a block is already queued.
	private class FakeBlockItem implements SendableRequestItem {
		
		public void dump() {
			// Do nothing
		}
		
		public SingleBlockInserter getParent() {
			return SingleBlockInserter.this;
		}

		@Override
		public int hashCode() {
			return SingleBlockInserter.this.hashCode();
		}
		
		@Override
		public boolean equals(Object o) {
			if(o instanceof BlockItem) {
				if(((BlockItem)o).parent == SingleBlockInserter.this) return true;
			} else if(o instanceof FakeBlockItem) {
				if(((FakeBlockItem)o).getParent() == SingleBlockInserter.this) return true;
			}
			return false;
		}
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "removeFrom() on "+this);
		container.activate(uri, 5);
		uri.removeFrom(container);
		if(resultingURI != null) {
			container.activate(resultingURI, 5);
			resultingURI.removeFrom(container);
		}
		// cb, parent are responsible for removing themselves
		// ctx is passed in and unmodified - usually the ClientPutter removes it
		container.activate(errors, 5);
		errors.removeFrom(container);
		if(freeData && sourceData != null && container.ext().isStored(sourceData)) {
			Logger.error(this, "Data not removed!");
			container.activate(sourceData, 1);
			sourceData.removeFrom(container);
		}
		container.delete(this);
	}

	@Override
	public boolean canWriteClientCache(ObjectContainer container) {
		boolean deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(ctx);
			if(deactivate)
				container.activate(ctx, 1);
		}
		boolean retval = ctx.canWriteClientCache;
		if(deactivate)
			container.deactivate(ctx, 1);
		return retval;
	}
	
	@Override
	public boolean forkOnCacheable(ObjectContainer container) {
		boolean deactivate = false;
		if(persistent) {
			deactivate = !container.ext().isActive(ctx);
			if(deactivate)
				container.activate(ctx, 1);
		}
		boolean retval = ctx.forkOnCacheable;
		if(deactivate)
			container.deactivate(ctx, 1);
		return retval;
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		if(finished) {
			Logger.error(this, "objectCanNew when already finished on "+this);
			return false;
		}
		if(logDEBUG)
			Logger.debug(this, "objectCanNew() on "+this, new Exception("debug"));
		return true;
	}
//	
//	public boolean objectCanUpdate(ObjectContainer container) {
//		Logger.minor(this, "objectCanUpdate() on "+this, new Exception("debug"));
//		return true;
//	}
//	
	
	public boolean isStorageBroken(ObjectContainer container) {
		if(parent == null) return true;
		if(ctx == null) return true;
		return false;
	}
	
}
