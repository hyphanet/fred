/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.events.SendingToNetworkEvent;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

/** A high level insert. */
public class ClientPutter extends BaseClientPutter implements PutCompletionCallback {

	/** Callback for when the insert completes. */
	final ClientPutCallback client;
	/** The data to insert. */
	final Bucket data;
	/** The URI to insert it to. Can be CHK@. */
	final FreenetURI targetURI;
	/** The ClientMetadata i.e. the MIME type and any other client-visible metadata. */
	final ClientMetadata cm;
	/** Config settings for this insert - what kind of splitfile to use if needed etc. */
	final InsertContext ctx;
	/** Target filename. If specified, we create manifest metadata so that the file can be accessed at
	 * [ final key ] / [ target filename ]. */
	final String targetFilename;
	/** The current state of the insert. */
	private ClientPutState currentState;
	/** Whether the insert has finished. */
	private boolean finished;
	/** If true, don't actually insert the data, just figure out what the final key would be. */
	private final boolean getCHKOnly;
	/** Are we inserting metadata? */
	private final boolean isMetadata;
	private boolean startedStarting;
	/** Are we inserting a binary blob? */
	private final boolean binaryBlob;
	/** The final URI for the data. */
	private FreenetURI uri;
	private final byte[] overrideSplitfileCrypto;
	/** Random or overriden splitfile cryptokey. Valid after start(). */
	private byte[] cryptoKey;
	/** When positive, means we will return metadata rather than a URI, once the
	 * metadata is under this length. If it is too short it is still possible to
	 * return a URI, but we won't return both. */
	private final long metadataThreshold;
	private boolean gotFinalMetadata;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	/**
	 * zero arg c'tor for db4o on jamvm
	 */
	@SuppressWarnings("unused")
	private ClientPutter() {
		targetURI = null;
		targetFilename = null;
		overrideSplitfileCrypto = null;
		isMetadata = false;
		getCHKOnly = false;
		data = null;
		ctx = null;
		cm = null;
		client = null;
		binaryBlob = false;
		metadataThreshold = 0;
	}

	/**
	 * @param client The object to call back when we complete, or don't.
	 * @param data The data to insert. This will not be freed by ClientPutter, the callback must do that. However,
	 * buckets used internally by the client layer will be freed.
	 * @param targetURI
	 * @param cm
	 * @param ctx
	 * @param scheduler
	 * @param priorityClass
	 * @param getCHKOnly
	 * @param isMetadata
	 * @param clientContext The client object for purposs of round-robin client balancing.
	 * @param targetFilename If set, create a one-file manifest containing this filename pointing to this file.
	 */
	public ClientPutter(ClientPutCallback client, Bucket data, FreenetURI targetURI, ClientMetadata cm, InsertContext ctx,
			short priorityClass, boolean getCHKOnly,
			boolean isMetadata, RequestClient clientContext, String targetFilename, boolean binaryBlob, ClientContext context, byte[] overrideSplitfileCrypto,
			long metadataThreshold) {
		super(priorityClass, clientContext);
		this.cm = cm;
		this.isMetadata = isMetadata;
		this.getCHKOnly = getCHKOnly;
		this.client = client;
		this.data = data;
		this.targetURI = targetURI.clone();
		this.ctx = ctx;
		this.finished = false;
		this.cancelled = false;
		this.targetFilename = targetFilename;
		this.binaryBlob = binaryBlob;
		this.overrideSplitfileCrypto = overrideSplitfileCrypto;
		this.metadataThreshold = metadataThreshold;
	}

	/** Start the insert.
	 * @param earlyEncode If true, try to find the final URI as quickly as possible, and insert the upper
	 * layers as soon as we can, rather than waiting for the lower layers. The default behaviour is safer,
	 * because an attacker can usually only identify the datastream once he has the top block, or once you
	 * have announced the key.
	 * @param container The database. If the insert is persistent, this must be non-null, and we must be
	 * running on the database thread. This is true for all methods taking a container parameter.
	 * @param context Contains some useful transient fields such as the schedulers.
	 * @throws InsertException If the insert cannot be started for some reason.
	 */
	public void start(boolean earlyEncode, ObjectContainer container, ClientContext context) throws InsertException {
		start(earlyEncode, false, container, context);
	}

	/** Start the insert.
	 * @param earlyEncode If true, try to find the final URI as quickly as possible, and insert the upper
	 * layers as soon as we can, rather than waiting for the lower layers. The default behaviour is safer,
	 * because an attacker can usually only identify the datastream once he has the top block, or once you
	 * have announced the key.
	 * @param restart If true, restart the insert even though it has completed before.
	 * @param container The database. If the insert is persistent, this must be non-null, and we must be
	 * running on the database thread. This is true for all methods taking a container parameter.
	 * @param context Contains some useful transient fields such as the schedulers.
	 * @throws InsertException If the insert cannot be started for some reason.
	 */
	public boolean start(boolean earlyEncode, boolean restart, ObjectContainer container, ClientContext context) throws InsertException {
		if(persistent()) {
			container.activate(ctx, 1);
			container.activate(client, 1);
		}
		if(logMINOR)
			Logger.minor(this, "Starting "+this+" for "+targetURI);
		byte cryptoAlgorithm;
		CompatibilityMode mode = ctx.getCompatibilityMode();
		if(!(mode == CompatibilityMode.COMPAT_CURRENT || mode.ordinal() >= CompatibilityMode.COMPAT_1416.ordinal()))
			cryptoAlgorithm = Key.ALGO_AES_PCFB_256_SHA256;
		else
			cryptoAlgorithm = Key.ALGO_AES_CTR_256_SHA256;
		try {
			this.targetURI.checkInsertURI();
			// If the top level key is an SSK, all CHK blocks and particularly splitfiles below it should have
			// randomised keys. This substantially improves security by making it impossible to identify blocks
			// even if you know the content. In the user interface, we will offer the option of inserting as a
			// random SSK to take advantage of this.
			boolean randomiseSplitfileKeys = randomiseSplitfileKeys(targetURI, ctx, persistent(), container);

			if(data == null)
				throw new InsertException(InsertException.BUCKET_ERROR, "No data to insert", null);

			boolean cancel = false;
			synchronized(this) {
				if(restart) {
					clearCountersOnRestart();
					if(currentState != null && !finished) {
						if(logMINOR) Logger.minor(this, "Can't restart, not finished and currentState != null : "+currentState);
						return false;
					}
					finished = false;
				}
				if(startedStarting) {
					if(logMINOR) Logger.minor(this, "Can't "+(restart?"restart":"start")+" : startedStarting = true");
					return false;
				}
				startedStarting = true;
				if(currentState != null) {
					if(logMINOR) Logger.minor(this, "Can't "+(restart?"restart":"start")+" : currentState != null : "+currentState);
					return false;
				}
				cancel = this.cancelled;
				cryptoKey = null;
				if(overrideSplitfileCrypto != null) {
					cryptoKey = overrideSplitfileCrypto;
					if (cryptoKey.length != 32)
						throw new InsertException(InsertException.INVALID_URI, "overrideSplitfileCryptoKey must be of length 32", null);
				} else if(randomiseSplitfileKeys) {
					cryptoKey = new byte[32];
					context.random.nextBytes(cryptoKey);
				}
				if(!cancel) {
					if(!binaryBlob) {
						ClientMetadata meta = cm;
						if(meta != null) meta = persistent() ? meta.clone() : meta;
						currentState =
							new SingleFileInserter(this, this, new InsertBlock(data, meta, persistent() ? targetURI.clone() : targetURI), isMetadata, ctx, realTimeFlag, 
									false, getCHKOnly, false, null, null, false, targetFilename, earlyEncode, false, persistent(), 0, 0, null, cryptoAlgorithm, cryptoKey, metadataThreshold);
					} else
						currentState =
							new BinaryBlobInserter(data, this, getClient(), false, priorityClass, ctx, context, container);
				}
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
				return false;
			}
			synchronized(this) {
				cancel = cancelled;
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
				if(persistent())
					container.store(this);
				return false;
			}
			if(logMINOR)
				Logger.minor(this, "Starting insert: "+currentState);
			if(currentState instanceof SingleFileInserter)
				((SingleFileInserter)currentState).start(container, context);
			else
				currentState.schedule(container, context);
			synchronized(this) {
				cancel = cancelled;
			}
			if(persistent()) {
				container.store(this);
				// It has scheduled, we can safely deactivate it now, so it won't hang around in memory.
				container.deactivate(currentState, 1);
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
				return false;
			}
		} catch (InsertException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				currentState = null;
			}
			if(persistent())
				container.store(this);
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(e, this, container);
			}
		} catch (IOException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				currentState = null;
			}
			if(persistent())
				container.store(this);
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, null), this, container);
			}
		} catch (BinaryBlobFormatException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				currentState = null;
			}
			if(persistent())
				container.store(this);
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(new InsertException(InsertException.BINARY_BLOB_FORMAT_ERROR, e, null), this, container);
			}
		}
		if(logMINOR)
			Logger.minor(this, "Started "+this);
		return true;
	}

	public static boolean randomiseSplitfileKeys(FreenetURI targetURI, InsertContext ctx, boolean persistent, ObjectContainer container) {
		// If the top level key is an SSK, all CHK blocks and particularly splitfiles below it should have
		// randomised keys. This substantially improves security by making it impossible to identify blocks
		// even if you know the content. In the user interface, we will offer the option of inserting as a
		// random SSK to take advantage of this.
		boolean randomiseSplitfileKeys = targetURI.isSSK() || targetURI.isKSK() || targetURI.isUSK();
		if(randomiseSplitfileKeys) {
			boolean ctxActive = true;
			if(persistent) {
				ctxActive = container.ext().isActive(ctx) || !container.ext().isStored(ctx);
				if(ctxActive) container.activate(ctx, 1);
			}
			CompatibilityMode cmode = ctx.getCompatibilityMode();
			if(!(cmode == CompatibilityMode.COMPAT_CURRENT || cmode.ordinal() >= CompatibilityMode.COMPAT_1255.ordinal()))
				randomiseSplitfileKeys = false;
			if(!ctxActive)
				container.deactivate(ctx, 1);
		}
		return randomiseSplitfileKeys;
	}

	/** Called when the insert succeeds. */
	@Override
	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(client, 1);
		ClientPutState oldState;
		synchronized(this) {
			finished = true;
			oldState = currentState;
			currentState = null;
		}
		if(oldState != null && persistent()) {
			container.activate(oldState, 1);
			oldState.removeFrom(container, context);
		}
		if(state != null && state != oldState && persistent())
			state.removeFrom(container, context);
		if(super.failedBlocks > 0 || super.fatallyFailedBlocks > 0 || super.successfulBlocks < super.totalBlocks) {
			if(persistent()) container.activate(uri, 1);
			// USK auxiliary inserts are allowed to fail.
			if(!uri.isUSK())
				Logger.error(this, "Failed blocks: "+failedBlocks+", Fatally failed blocks: "+fatallyFailedBlocks+
						", Successful blocks: "+successfulBlocks+", Total blocks: "+totalBlocks+" but success?! on "+this+" from "+state,
						new Exception("debug"));
		}
		if(persistent())
			container.store(this);
		client.onSuccess(this, container);
	}

	/** Called when the insert fails. */
	@Override
	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "onFailure() for "+this+" : "+state+" : "+e, e);
		if(persistent())
			container.activate(client, 1);
		ClientPutState oldState;
		synchronized(this) {
			finished = true;
			oldState = currentState;
			currentState = null;
		}
		if(oldState != null && persistent()) {
			container.activate(oldState, 1);
			oldState.removeFrom(container, context);
		}
		if(state != null && state != oldState && persistent())
			state.removeFrom(container, context);
		if(persistent())
			container.store(this);
		client.onFailure(e, this, container);
	}

	/** Called when significant milestones are passed. */
	@Override
	public void onMajorProgress(ObjectContainer container) {
		if(persistent())
			container.activate(client, 1);
		client.onMajorProgress(container);
	}

	/** Called when we know the final URI of the insert. */
	@Override
	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(client, 1);
		FreenetURI u;
		synchronized(this) {
			if(this.uri != null) {
				Logger.error(this, "onEncode() called twice? Already have a uri: "+uri+" for "+this);
				if(persistent())
					this.uri.removeFrom(container);
			}
			if(gotFinalMetadata) {
				Logger.error(this, "Generated URI *and* sent final metadata??? on "+this+" from "+state);
			}
			this.uri = key.getURI();
			if(targetFilename != null)
				uri = uri.pushMetaString(targetFilename);
			u = uri;
		}
		if(persistent()) {
			container.store(this);
			u = u.clone();
		}
		client.onGeneratedURI(uri, this, container);
	}
	
	/** Called when metadataThreshold was specified and metadata is being returned
	 * instead of a URI. */
	public void onMetadata(Bucket finalMetadata, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(client, 1);
		boolean freeIt = false;
		synchronized(this) {
			if(uri != null) {
				Logger.error(this, "Generated URI *and* sent final metadata??? on "+this+" from "+state);
			}
			if(gotFinalMetadata) {
				Logger.error(this, "onMetadata called twice - already sent metadata to client for "+this);
				freeIt = true;
			} else {
				gotFinalMetadata = true;
			}
		}
		if(freeIt) {
			finalMetadata.free();
			finalMetadata.removeFrom(container);
			return;
		}
		if(persistent()) {
			container.store(this);
		}
		client.onGeneratedMetadata(finalMetadata, this, container);
	}

	/** Cancel the insert. Will call onFailure() if it is not already cancelled, so the callback will
	 * normally be called. */
	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Cancelling "+this, new Exception("debug"));
		ClientPutState oldState = null;
		synchronized(this) {
			if(cancelled) return;
			if(finished) return;
			super.cancel();
			oldState = currentState;
		}
		if(persistent()) {
			container.store(this);
			if(oldState != null)
				container.activate(oldState, 1);
		}
		if(oldState != null) oldState.cancel(container, context);
		onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
	}

	/** Has the insert completed already? */
	@Override
	public synchronized boolean isFinished() {
		return finished || cancelled;
	}
	
	/**
	 * @return The data {@link Bucket} which is used by this ClientPutter.
	 */
	public Bucket getData() {
		return data;
	}
	
	/**
	 * Get the target URI with which this insert was started.
	 */
	public FreenetURI getTargetURI() {
		return targetURI;
	}

	/** Get the final URI to the inserted data */
	@Override
	public FreenetURI getURI() {
		return uri;
	}

	/**
	 * Get used splitfile cryptokey. Valid only after start().
	 */
	public byte[] getSplitfileCryptoKey() {
		return cryptoKey;
	}

	/** Called when a ClientPutState transitions to a new state. If this is the current state, then we update
	 * it, but it might also be a subsidiary state, in which case we ignore it. */
	@Override
	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		if(newState == null) throw new NullPointerException();

		// onTransition is *not* responsible for removing the old state, the caller is.
		synchronized (this) {
			if (currentState == oldState) {
				currentState = newState;
				if(persistent())
					container.store(this);
				return;
			}
		}
		Logger.error(this, "onTransition: cur=" + currentState + ", old=" + oldState + ", new=" + newState);
	}

	/** Called when we have generated metadata for the insert. This should not happen, because we should
	 * insert the metadata! */
	@Override
	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		Logger.error(this, "Got metadata on "+this+" from "+state+" (this means the metadata won't be inserted)");
	}

	/** The number of blocks that will be needed to fetch the data. We put this in the top block metadata. */
	protected int minSuccessFetchBlocks;
	
	@Override
	public int getMinSuccessFetchBlocks() {
		return minSuccessFetchBlocks;
	}
	
	@Override
	public void addBlock(ObjectContainer container) {
		synchronized(this) {
			minSuccessFetchBlocks++;
		}
		super.addBlock(container);
	}
	
	@Override
	public void addBlocks(int num, ObjectContainer container) {
		synchronized(this) {
			minSuccessFetchBlocks+=num;
		}
		super.addBlocks(num, container);
	}
	
	/** Add one or more blocks to the number of requires blocks, and don't notify the clients. */
	@Override
	public void addMustSucceedBlocks(int blocks, ObjectContainer container) {
		synchronized(this) {
			minSuccessFetchBlocks += blocks;
		}
		super.addMustSucceedBlocks(blocks, container);
	}

	/** Add one or more blocks to the number of requires blocks, and don't notify the clients. 
	 * These blocks are added to the minSuccessFetchBlocks for the insert, but not to the counter for what
	 * the requestor must fetch. */
	@Override
	public void addRedundantBlocks(int blocks, ObjectContainer container) {
		super.addMustSucceedBlocks(blocks, container);
	}
	
	@Override
	protected void clearCountersOnRestart() {
		minSuccessFetchBlocks = 0;
		super.clearCountersOnRestart();
	}

	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(ctx, 2);
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.minSuccessFetchBlocks, this.blockSetFinalized), container, context);
	}

	/** Notify listening clients that an insert has been sent to the network. */
	@Override
	protected void innerToNetwork(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(ctx, 1);
			container.activate(ctx.eventProducer, 1);
		}
		ctx.eventProducer.produceEvent(new SendingToNetworkEvent(), container, context);
	}

	/** Called when we know exactly how many blocks will be needed. */
	@Override
	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized(container, context);
	}

	/** Called (sometimes) when enough of the data has been inserted that the file can now be fetched. Not
	 * very useful unless earlyEncode was enabled. */
	@Override
	public void onFetchable(ClientPutState state, ObjectContainer container) {
		if(persistent())
			container.activate(client, 1);
		client.onFetchable(this, container);
	}

	/** Can we restart the insert? */
	public boolean canRestart() {
		if(currentState != null && !finished) {
			Logger.minor(this, "Cannot restart because not finished for "+uri);
			return false;
		}
		if(data == null) return false;
		return true;
	}

	/** Restart the insert.
	 * @param earlyEncode See the description on @link start().
	 * @param container The database. If the insert is persistent, this must be non-null, and we must be
	 * running on the database thread. This is true for all places where we pass in an ObjectContainer.
	 * @return True if the insert restarted successfully.
	 * @throws InsertException If the insert could not be restarted for some reason.
	 * */
	public boolean restart(boolean earlyEncode, ObjectContainer container, ClientContext context) throws InsertException {
		checkForBrokenClient(container, context);
		return start(earlyEncode, true, container, context);
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore, at the moment
	}

	/** Remove the ClientPutter from the database. */
	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		container.activate(cm, 2);
		cm.removeFrom(container);
		// This is passed in. We should not remove it, because the caller (ClientPutBase) should remove it.
//		container.activate(ctx, 1);
//		ctx.removeFrom(container);
		container.activate(targetURI, 5);
		targetURI.removeFrom(container);
		if(uri != null) {
			container.activate(uri, 5);
			uri.removeFrom(container);
		}
		super.removeFrom(container, context);
	}

	@Override
	public void dump(ObjectContainer container) {
		container.activate(uri, 5);
		System.out.println("URI: "+uri);
		container.activate(client, 1);
		System.out.println("Client: "+client);
		System.out.println("Finished: "+finished);
		container.activate(data, 5);
		System.out.println("Data: "+data);
	}
	
}
