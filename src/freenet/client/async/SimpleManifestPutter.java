package freenet.client.async;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.NativeThread;
import freenet.support.io.Closer;

public class SimpleManifestPutter extends ManifestPutter implements PutCompletionCallback {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	// Only implements PutCompletionCallback for the final metadata insert
	private class PutHandler extends BaseClientPutter implements PutCompletionCallback {

		/**
		 * zero arg c'tor for db4o on jamvm
		 */
		@SuppressWarnings("unused")
		private PutHandler() {
			persistent = false;
			data = null;
			containerHandle = null;
		}

		protected PutHandler(final SimpleManifestPutter smp, String name, Bucket data, ClientMetadata cm, boolean getCHKOnly, boolean persistent) {
			super(smp.priorityClass, smp.client);
			this.persistent = persistent;
			this.cm = cm;
			this.data = data;
			InsertBlock block =
				new InsertBlock(data, cm, persistent() ? FreenetURI.EMPTY_CHK_URI.clone() : FreenetURI.EMPTY_CHK_URI);
			this.origSFI =
				new SingleFileInserter(this, this, block, false, ctx, realTimeFlag, false, getCHKOnly, true, null, null, false, null, earlyEncode, false, persistent, 0, 0, null, cryptoAlgorithm, forceCryptoKey, -1);
			metadata = null;
			containerHandle = null;
		}

		protected PutHandler(final SimpleManifestPutter smp, String name, FreenetURI target, ClientMetadata cm, boolean persistent) {
			super(smp.getPriorityClass(), smp.client);
			this.persistent = persistent;
			this.cm = cm;
			this.data = null;
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, target, cm);
			metadata = m;
			if(logMINOR) Logger.minor(this, "Simple redirect metadata: "+m);
			origSFI = null;
			containerHandle = null;
		}

		protected PutHandler(final SimpleManifestPutter smp, String name, String targetInArchive, ClientMetadata cm, Bucket data, boolean persistent) {
			super(smp.getPriorityClass(), smp.client);
			this.persistent = persistent;
			this.cm = cm;
			this.data = data;
			this.targetInArchive = targetInArchive;
			Metadata m = new Metadata(Metadata.ARCHIVE_INTERNAL_REDIRECT, null, null, targetInArchive, cm);
			metadata = m;
			if(logMINOR) Logger.minor(this, "Internal redirect: "+m);
			origSFI = null;
			containerHandle = null;
		}

		private ClientPutState origSFI;
		private ClientPutState currentState;
		private ClientMetadata cm;
		private Metadata metadata;
		private String targetInArchive;
		private final Bucket data;
		private final boolean persistent;
		private final PutHandler containerHandle;

		public void start(ObjectContainer container, ClientContext context) throws InsertException {
			if (origSFI == null) {
				 Logger.error(this, "origSFI is null on start(), should be impossible", new Exception("debug"));
				 return;
			}
			if (metadata != null) {
				Logger.error(this, "metdata=" + metadata + " on start(), should be impossible", new Exception("debug"));
				return;
			}
			ClientPutState sfi;
			synchronized(this) {
				sfi = origSFI;
				currentState = sfi;
				origSFI = null;
			}
			if(persistent) {
				container.activate(sfi, 1);
				container.store(this);
			}
			sfi.schedule(container, context);
			if(persistent) {
				container.deactivate(sfi, 1);
			}
		}

		@Override
		public void cancel(ObjectContainer container, ClientContext context) {
			if(logMINOR)
				Logger.minor(this, "Cancelling "+this, new Exception("debug"));
			ClientPutState oldState = null;
			synchronized(this) {
				if(cancelled) return;
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

		@Override
		public FreenetURI getURI() {
			return null;
		}

		@Override
		public boolean isFinished() {
			return SimpleManifestPutter.this.finished || cancelled || SimpleManifestPutter.this.cancelled;
		}

		@Override
		public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "Completed "+this);
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
				container.activate(runningPutHandlers, 2);
			}
			SimpleManifestPutter.this.onFetchable(this, container);
			ClientPutState oldState;
			boolean insertedAllFiles = true;
			synchronized(this) {
				oldState = currentState;
				currentState = null;
			}
			synchronized(SimpleManifestPutter.this) {
				if(persistent) container.store(this);
				runningPutHandlers.remove(this);
				if(persistent) {
					container.ext().store(runningPutHandlers, 2);
					container.activate(putHandlersWaitingForMetadata, 2);
				}
				if(putHandlersWaitingForMetadata.remove(this)) {
					if(persistent) container.ext().store(putHandlersWaitingForMetadata, 2);
					Logger.error(this, "PutHandler was in waitingForMetadata in onSuccess() on "+this+" for "+SimpleManifestPutter.this);
				}

				if(persistent) {
					container.deactivate(putHandlersWaitingForMetadata, 1);
					container.activate(waitingForBlockSets, 2);
				}
				if(waitingForBlockSets.remove(this)) {
					if(persistent) container.store(waitingForBlockSets);
					Logger.error(this, "PutHandler was in waitingForBlockSets in onSuccess() on "+this+" for "+SimpleManifestPutter.this);
				}
				if(persistent) {
					container.deactivate(waitingForBlockSets, 1);
					container.deactivate(putHandlersWaitingForFetchable, 1);
					container.activate(putHandlersWaitingForFetchable, 2);
				}
				if(putHandlersWaitingForFetchable.remove(this)) {
					if(persistent) container.ext().store(putHandlersWaitingForFetchable, 2);
					// Not getting an onFetchable is not unusual, just ignore it.
					if(logMINOR) Logger.minor(this, "PutHandler was in waitingForFetchable in onSuccess() on "+this+" for "+SimpleManifestPutter.this);
				}
				if(persistent)
					container.deactivate(putHandlersWaitingForFetchable, 1);

				if(!runningPutHandlers.isEmpty()) {
					if(logMINOR) {
						Logger.minor(this, "Running put handlers: "+runningPutHandlers.size());
						for(Object o : runningPutHandlers) {
							boolean activated = true;
							if(persistent) {
								activated = container.ext().isActive(o);
								if(!activated) container.activate(o, 1);
							}
							Logger.minor(this, "Still running: "+o);
							if(!activated)
								container.deactivate(o, 1);
						}
					}
					insertedAllFiles = false;
				}
			}
			if(oldState != null && oldState != state && persistent) {
				container.activate(oldState, 1);
				oldState.removeFrom(container, context);
			} else if(state != null && persistent) {
				state.removeFrom(container, context);
			}
			if(insertedAllFiles)
				insertedAllFiles(container, context);
			if(persistent) {
				container.deactivate(runningPutHandlers, 1);
				container.deactivate(SimpleManifestPutter.this, 1);
			}
		}

		@Override
		public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
			ClientPutState oldState;
			synchronized(this) {
				oldState = currentState;
				currentState = null;
			}
			if(oldState != null && oldState != state && persistent) {
				container.activate(oldState, 1);
				oldState.removeFrom(container, context);
			} else if(state != null && persistent) {
				state.removeFrom(container, context);
			}
			if(logMINOR) Logger.minor(this, "Failed: "+this+" - "+e, e);
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			fail(e, container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		@Override
		public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "onEncode("+key+") for "+this);
			if(metadata == null) {
				// The file was too small to have its own metadata, we get this instead.
				// So we make the key into metadata.
				if(persistent) {
					container.activate(key, 5);
					container.activate(SimpleManifestPutter.this, 1);
				}
				Metadata m =
					new Metadata(Metadata.SIMPLE_REDIRECT, null, null, key.getURI(), cm);
				onMetadata(m, null, container, context);
				if(persistent) {
					container.deactivate(SimpleManifestPutter.this, 1);
				}
			}
		}

		/**
		 * The caller of onTransition removes the old state, so we don't have to.
		 * However, in onSuccess or onFailure, we need to remove the new state, even if
		 * what is passed in is different (in which case we remove that too).
		 */
		@Override
		public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
			if(newState == null) throw new NullPointerException();

			// onTransition is *not* responsible for removing the old state, the caller is.
			synchronized (this) {
				if (currentState == oldState) {
					currentState = newState;
					if(persistent())
						container.store(this);
					if(logMINOR)
						Logger.minor(this, "onTransition: cur=" + currentState + ", old=" + oldState + ", new=" + newState+" for "+this);
					return;
				}
				Logger.error(this, "Ignoring onTransition: cur=" + currentState + ", old=" + oldState + ", new=" + newState+" for "+this);
			}
		}

		@Override
		public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "Assigning metadata: "+m+" for "+this+" from "+state+" persistent="+persistent,
					new Exception("debug"));
			if(metadata != null) {
				Logger.error(this, "Reassigning metadata", new Exception("debug"));
				return;
			}
			metadata = m;
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
				container.activate(putHandlersWaitingForMetadata, 2);
			}

			boolean allMetadatas = false;

			synchronized(SimpleManifestPutter.this) {
				putHandlersWaitingForMetadata.remove(this);
				if(persistent) {
					container.ext().store(putHandlersWaitingForMetadata, 2);
					container.store(this);
				}
				allMetadatas = putHandlersWaitingForMetadata.isEmpty();
				if(!allMetadatas) {
					if(logMINOR)
						Logger.minor(this, "Still waiting for metadata: "+putHandlersWaitingForMetadata.size());
				}
			}
			if(allMetadatas) {
				// Will resolve etc.
				gotAllMetadata(container, context);
			} else {
				// Resolve now to speed up the insert.
				try {
					if(persistent)
						container.activate(m, Integer.MAX_VALUE);
					byte[] buf = m.writeToByteArray();
					if(buf.length > Metadata.MAX_SIZE_IN_MANIFEST)
						throw new MetadataUnresolvedException(new Metadata[] { m }, "Too big");
				} catch (MetadataUnresolvedException e) {
					try {
						resolve(e, container, context);
					} catch (IOException e1) {
						fail(new InsertException(InsertException.BUCKET_ERROR, e1, null), container, context);
						return;
					} catch (InsertException e1) {
						fail(e1, container, context);
					}
				}
			}
			if(persistent) {
				container.deactivate(putHandlersWaitingForMetadata, 1);
				container.deactivate(SimpleManifestPutter.this, 1);
			}
		}
		
		@Override
		public void onMetadata(Bucket m, ClientPutState state, ObjectContainer container, ClientContext context) {
			throw new UnsupportedOperationException();
		}
		
		/** The number of blocks that will be needed to fetch the data. We put this in the top block metadata. */
		protected int minSuccessFetchBlocks;
		
		@Override
		public void addBlock(ObjectContainer container) {
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
			}
			SimpleManifestPutter.this.addBlock(container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
			synchronized(this) {
				minSuccessFetchBlocks++;
			}
			super.addBlock(container);
		}

		@Override
		public void addBlocks(int num, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.addBlocks(num, container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
			synchronized(this) {
				minSuccessFetchBlocks+=num;
			}
			super.addBlock(container);
		}

		@Override
		public void completedBlock(boolean dontNotify, ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.completedBlock(dontNotify, container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
			super.completedBlock(dontNotify, container, context);
		}

		@Override
		public void failedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.failedBlock(container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
			super.failedBlock(container, context);
		}

		@Override
		public void fatallyFailedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.fatallyFailedBlock(container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
			super.fatallyFailedBlock(container, context);
		}

		@Override
		public void addMustSucceedBlocks(int blocks, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.addMustSucceedBlocks(blocks, container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
			synchronized(this) {
				minSuccessFetchBlocks += blocks;
			}
			super.addMustSucceedBlocks(blocks, container);
		}

		@Override
		public void addRedundantBlocks(int blocks, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.addRedundantBlocks(blocks, container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
			super.addMustSucceedBlocks(blocks, container);
		}
		
		@Override
		public void notifyClients(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.notifyClients(container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}
		
		@Override
		public synchronized int getMinSuccessFetchBlocks() {
			return minSuccessFetchBlocks;
		}
		
		@Override
		public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
				container.activate(waitingForBlockSets, 2);
			}
			boolean allBlockSets = false;
			synchronized(SimpleManifestPutter.this) {
				waitingForBlockSets.remove(this);
				if(persistent)
					container.store(waitingForBlockSets);
				allBlockSets = waitingForBlockSets.isEmpty();
			}
			if(allBlockSets)
				SimpleManifestPutter.this.blockSetFinalized(container, context);
			if(persistent) {
				container.deactivate(waitingForBlockSets, 1);
				container.deactivate(SimpleManifestPutter.this, 1);
			}
		}

		@Override
		public void onMajorProgress(ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.onMajorProgress(container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		@Override
		public void onFetchable(ClientPutState state, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.onFetchable(this, container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		@Override
		public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
			// Ignore
		}

		public void clearMetadata(ObjectContainer container) {
			metadata = null;
			if(persistent) container.store(this);
		}

		@Override
		public void removeFrom(ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "Removing "+this);
			ClientPutState oldSFI;
			ClientPutState oldState;
			synchronized(this) {
				oldSFI = origSFI;
				oldState = currentState;
				origSFI = null;
				currentState = null;
			}
			if(oldSFI != null) {
				Logger.error(this, "origSFI is set in removeFrom() on "+this+" for "+SimpleManifestPutter.this, new Exception("debug"));
				container.activate(oldSFI, 1);
				oldSFI.cancel(container, context);
				oldSFI.removeFrom(container, context);
				if(oldState == oldSFI) oldState = null;
			}
			if(oldState != null) {
				Logger.error(this, "currentState is set in removeFrom() on "+this+" for "+SimpleManifestPutter.this, new Exception("debug"));
				container.activate(oldState, 1);
				oldState.cancel(container, context);
				oldState.removeFrom(container, context);
			}
			if(cm != null) {
				container.activate(cm, 5);
				cm.removeFrom(container);
			}
			if(metadata != null) {
				// Possible if cancelled
				Logger.normal(this, "Metadata is set in removeFrom() on "+this+" for "+SimpleManifestPutter.this);
				container.activate(metadata, 1);
				metadata.removeFrom(container);
			}
			// Data is responsibility of original caller (usually ClientPutDir), we don't support freeData atm
			super.removeFrom(container, context);
		}

		@Override
		public boolean objectCanNew(ObjectContainer container) {
			if(cancelled) {
				Logger.error(this, "Storing "+this+" when already cancelled!", new Exception("error"));
				return false;
			}
			if(logMINOR) Logger.minor(this, "Storing "+this+" activated="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("debug"));
			return true;
		}

		@Override
		protected void innerToNetwork(ObjectContainer container, ClientContext context) {
			// Ignore
		}

	}

	// FIXME: DB4O ISSUE: HASHMAP ACTIVATION:
	
	// Unfortunately this class uses a lot of HashMap's, and is persistent.
	// The two things do not play well together!
	
	// Activating a HashMap to depth 1 breaks it badly, so that even if it is then activated to a higher depth, it remains empty.
	// Activating a HashMap to depth 2 loads the elements but does not activate them. In particular, Metadata's used as keys will not have their hashCode loaded so we end up with all of them on the 0th slot.
	// Activating a HashMap to depth 3 loads it properly, including activating both the keys and values to depth 1.
	// Of course, the side effect of activating the values to depth 1 may cause problems ...

	// OPTIONS:
	// 1. Activate to depth 2. Activate the Metadata we are looking for *FIRST*!
	// Then the Metadata we are looking for will be in the correct slot.
	// Everything else will be in the 0'th slot, in one long chain, i.e. if there are lots of entries it will be a very inefficient HashMap.
	
	// 2. Activate to depth 3.
	// If there are lots of entries, we have a significant I/O cost for activating *all* of them.
	// We also have the possibility of a memory/space leak if these are linked from somewhere that assumed they had been deactivated.
	
	// Clearly option 1 is superior. However they both suck.
	// The *correct* solution is to use a HashMap from a primitive type e.g. a String, so we can use depth 2.
	
	// Note that this also applies to HashSet's: The entries are the keys, and they are not activated, so we end up with them all in a long chain off bucket 0, except any that are already active.
	// We don't have any real problems because the caller is generally already active - but it is grossly inefficient.
	
	private HashMap<String,Object> putHandlersByName;
	private HashSet<PutHandler> runningPutHandlers;
	private HashSet<PutHandler> putHandlersWaitingForMetadata;
	private HashSet<PutHandler> waitingForBlockSets;
	private HashSet<PutHandler> putHandlersWaitingForFetchable;
	private FreenetURI finalURI;
	private FreenetURI targetURI;
	private boolean finished;
	private final InsertContext ctx;
	final ClientPutCallback cb;
	private final boolean getCHKOnly;
	private boolean insertedAllFiles;
	private boolean insertedManifest;
	private final HashMap<Metadata,ClientPutState> metadataPuttersByMetadata;
	private final HashMap<Metadata,ClientPutState> metadataPuttersUnfetchable;
	private final String defaultName;
	private int numberOfFiles;
	private long totalSize;
	private boolean metadataBlockSetFinalized;
	private Metadata baseMetadata;
	private boolean hasResolvedBase;
	private final static String[] defaultDefaultNames =
		new String[] { "index.html", "index.htm", "default.html", "default.htm" };
	private int bytesOnZip;
	private ArrayList<PutHandler> elementsToPutInArchive;
	private boolean fetchable;
	private final boolean earlyEncode;
	final byte[] forceCryptoKey;
	final byte cryptoAlgorithm;

	/**
	 * zero arg c'tor for db4o on jamvm
	 */
	@SuppressWarnings("unused")
	private SimpleManifestPutter() {
		metadataPuttersUnfetchable = null;
		metadataPuttersByMetadata = null;
		getCHKOnly = false;
		forceCryptoKey = null;
		earlyEncode = false;
		defaultName = null;
		ctx = null;
		cryptoAlgorithm = 0;
		cb = null;
	}

	public SimpleManifestPutter(ClientPutCallback cb,
			HashMap<String, Object> manifestElements, short prioClass, FreenetURI target,
			String defaultName, InsertContext ctx, boolean getCHKOnly, RequestClient clientContext, boolean earlyEncode, boolean persistent, ObjectContainer container, ClientContext context) {
		this(cb, manifestElements, prioClass, target, defaultName, ctx, getCHKOnly, clientContext, earlyEncode, persistent, null, container, context);

	}
		
	private static byte[] getRandomSplitfileKeys(FreenetURI target,
			InsertContext ctx, boolean persistent, ObjectContainer container, ClientContext context) {
		boolean randomiseSplitfileKeys = ClientPutter.randomiseSplitfileKeys(target, ctx, persistent, container);
		if(randomiseSplitfileKeys) {
			byte[] forceCryptoKey = new byte[32];
			context.random.nextBytes(forceCryptoKey);
			return forceCryptoKey;
		} else {
			return null;
		}
	}

	public SimpleManifestPutter(ClientPutCallback cb,
			HashMap<String, Object> manifestElements, short prioClass, FreenetURI target,
			String defaultName, InsertContext ctx, boolean getCHKOnly, RequestClient clientContext, boolean earlyEncode, boolean persistent, byte[] forceCryptoKey, ObjectContainer container, ClientContext context) {
		super(prioClass, clientContext);
		this.defaultName = defaultName;
		
		if(defaultName != null) {
			if(persistent)
				container.activate(manifestElements, Integer.MAX_VALUE);
			checkDefaultName(manifestElements, defaultName);
		}

		if(client.persistent())
			container.activate(ctx, 1);
		CompatibilityMode mode = ctx.getCompatibilityMode();
		if(!(mode == CompatibilityMode.COMPAT_CURRENT || mode.ordinal() >= CompatibilityMode.COMPAT_1416.ordinal()))
			this.cryptoAlgorithm = Key.ALGO_AES_PCFB_256_SHA256;
		else
			this.cryptoAlgorithm = Key.ALGO_AES_CTR_256_SHA256;

		if(persistent)
			this.targetURI = target.clone();
		else
			this.targetURI = target;
		this.forceCryptoKey = forceCryptoKey != null ? forceCryptoKey : getRandomSplitfileKeys(target, ctx, persistent, container, context);
		this.cb = cb;
		this.ctx = ctx;
		this.getCHKOnly = getCHKOnly;
		this.earlyEncode = earlyEncode;
		putHandlersByName = new HashMap<String,Object>();
		runningPutHandlers = new HashSet<PutHandler>();
		putHandlersWaitingForMetadata = new HashSet<PutHandler>();
		putHandlersWaitingForFetchable = new HashSet<PutHandler>();
		waitingForBlockSets = new HashSet<PutHandler>();
		metadataPuttersByMetadata = new HashMap<Metadata,ClientPutState>();
		metadataPuttersUnfetchable = new HashMap<Metadata,ClientPutState>();
		elementsToPutInArchive = new ArrayList<PutHandler>();
		makePutHandlers(manifestElements, putHandlersByName, persistent);
		checkZips();
	}

	@SuppressWarnings("unchecked")
	static private void checkDefaultName(HashMap<String, Object> manifestElements,
			String defaultName) {
		int idx;
		while((idx = defaultName.indexOf('/')) != -1) {
			String dir = defaultName.substring(0, idx);
			String subname = defaultName.substring(idx+1);
			Object o = manifestElements.get(defaultName);
			if(o == null) throw new IllegalArgumentException("Default name dir \""+dir+"\" does not exist");
			if(!(o instanceof HashMap))
				throw new IllegalArgumentException("Default name dir \""+dir+"\" is not a directory in \""+defaultName+"\"");
			manifestElements = (HashMap<String, Object>)o;
			defaultName = subname;
		}
		Object o = manifestElements.get(defaultName);
		if(o == null) throw new IllegalArgumentException("Default name \""+defaultName+"\" does not exist");
		if(o instanceof HashMap) throw new IllegalArgumentException("Default filename \""+defaultName+"\" is a directory?!");
		// instanceof Bucket is checked in bucketsByNameToManifestEntries
	}

	private void checkZips() {
		// If there are too few files in the zip, then insert them directly instead.
		// FIXME do something.
	}

	public void start(ObjectContainer container, ClientContext context) throws InsertException {
		if (logMINOR)
			Logger.minor(this, "Starting " + this+" persistence="+persistent());
		PutHandler[] running;

		if(persistent()) {
			container.activate(runningPutHandlers, 2);
		}
		synchronized (this) {
			running = runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
		}
		try {
			boolean persistent = persistent(); // this might get deactivated ...
			for (int i = 0; i < running.length; i++) {
				if(logMINOR) Logger.minor(this, "Starting "+running[i]);
				running[i].start(container, context);
				synchronized(this) {
					// It might have failed to start.
					if(finished) return;
				}
				if(persistent && !container.ext().isActive(this))
					container.activate(this, 1);
				if (logMINOR)
					Logger.minor(this, "Started " + i + " of " + running.length);
				if (isFinished()) {
					if (logMINOR)
						Logger.minor(this, "Already finished, killing start() on " + this);
					return;
				}
			}
			if (logMINOR)
				Logger.minor(this, "Started " + running.length + " PutHandler's for " + this);
			if (running.length == 0) {
				insertedAllFiles = true;
				if(persistent())
					container.store(this);
				gotAllMetadata(container, context);
			}
		} catch (InsertException e) {
			synchronized(this) {
				finished = true;
			}
			cancelAndFinish(container, context);
			throw e;
		}
	}

	protected void makePutHandlers(HashMap<String, Object> manifestElements, HashMap<String,Object> putHandlersByName, boolean persistent) {
		makePutHandlers(manifestElements, putHandlersByName, "", persistent);
	}

	private void makePutHandlers(HashMap<String, Object> manifestElements, HashMap<String,Object> putHandlersByName, String ZipPrefix, boolean persistent) {
		for(Map.Entry<String, Object> entry: manifestElements.entrySet()) {
			String name = entry.getKey();
			Object o = entry.getValue();
			if (o instanceof HashMap) {
				HashMap<String,Object> subMap = new HashMap<String,Object>();
				HashMap<String,Object> elements = Metadata.forceMap(o);
				putHandlersByName.put(name, subMap);
				makePutHandlers(elements, subMap, ZipPrefix+name+ '/', persistent);
				if(logDEBUG)
					Logger.debug(this, "Sub map for "+name+" : "+subMap.size()+" elements from "+elements.size());
			} else {
				ManifestElement element = (ManifestElement) o;
				String mimeType = element.mimeOverride;
				if(mimeType == null)
					mimeType = DefaultMIMETypes.guessMIMEType(name, true);
				ClientMetadata cm;
				if(mimeType == null || mimeType.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE))
					cm = null;
				else
					cm = new ClientMetadata(mimeType);
				PutHandler ph;
				Bucket data = element.data;
				if(element.targetURI != null) {
					ph = new PutHandler(this, name, element.targetURI, cm, persistent);
					// Just a placeholder, don't actually run it
				} else {
					// Decide whether to put it in the ZIP.
					// FIXME support multiple ZIPs and size limits.
					// FIXME support better heuristics.
					// ZIP is slightly more compact, can use this formula:
					//int sz = (int)data.size() + 40 + element.fullName.length();
					// TAR is less compact (but with a chained compressor is far more efficient due to inter-file compression):
					int sz = 512 + (((((int)data.size()) + 511) / 512) * 512);
					if((data.size() <= 65536) &&
							// tar pads to next 10k, and has 1k end-headers
							// FIXME this also needs to include the metadata!!
							// FIXME no way to know at this point how big that is...
							// need major refactoring to make this work... for now just
							// assume 64k will cover it.
							(bytesOnZip + sz < (2038-64)*1024)) { // totally dumb heuristic!
						bytesOnZip += sz;
						// Put it in the zip.
						if(logMINOR)
							Logger.minor(this, "Putting into ZIP: "+name);
						ph = new PutHandler(this, name, ZipPrefix+element.fullName, cm, data, persistent);
						if(logMINOR)
							Logger.minor(this, "Putting file into container: "+element.fullName+" : "+ph);
						elementsToPutInArchive.add(ph);
						numberOfFiles++;
						totalSize += data.size();
					} else {
							ph = new PutHandler(this,name, data, cm, getCHKOnly, persistent);
						runningPutHandlers.add(ph);
						putHandlersWaitingForMetadata.add(ph);
						putHandlersWaitingForFetchable.add(ph);
						if(logMINOR)
							Logger.minor(this, "Inserting separately as PutHandler: "+name+" : "+ph+" persistent="+ph.persistent()+":"+ph.persistent+" "+persistent());
						numberOfFiles++;
						totalSize += data.size();
					}
				}
				putHandlersByName.put(name, ph);
			}
		}
	}

	@Override
	public FreenetURI getURI() {
		return finalURI;
	}

	@Override
	public synchronized boolean isFinished() {
		return finished || cancelled;
	}

	@Override
	public byte[] getSplitfileCryptoKey() {
		return forceCryptoKey;
	}

	private final DBJob runGotAllMetadata = new DBJob() {

		@Override
		public boolean run(ObjectContainer container, ClientContext context) {
			try {
				context.jobRunner.removeRestartJob(this, NativeThread.NORM_PRIORITY, container);
			} catch (DatabaseDisabledException e) {
				// Impossible
				return false;
			}
			container.activate(SimpleManifestPutter.this, 1);
			innerGotAllMetadata(container, context);
			container.deactivate(SimpleManifestPutter.this, 1);
			return true;
		}

	};

	/**
	 * Called when we have metadata for all the PutHandler's.
	 * This does *not* necessarily mean we can immediately insert the final metadata, since
	 * if these metadata's are too big, they will need to be inserted separately. See
	 * resolveAndStartBase().
	 * @param container
	 * @param context
	 */
	private void gotAllMetadata(ObjectContainer container, ClientContext context) {
		// This can be huge! Run it on its own transaction to minimize the build up of stuff to commit
		// and maximise the opportunities for garbage collection.
		if(persistent()) {
			container.activate(runGotAllMetadata, 1); // need to activate .this!
			try {
				context.jobRunner.queueRestartJob(runGotAllMetadata, NativeThread.NORM_PRIORITY, container, false);
				context.jobRunner.queue(runGotAllMetadata, NativeThread.NORM_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
				// Impossible
				return;
			}
		} else {
			innerGotAllMetadata(null, context);
		}
	}

	/**
	 * Generate the global metadata, and then call resolveAndStartBase.
	 * @param container
	 * @param context
	 */
	private void innerGotAllMetadata(ObjectContainer container, ClientContext context) {
		/** COR-1582: We have to carefully avoid activating any hashmap to depth 2,
		 * however we cannot afford the memory to activate everything to max depth in
		 * advance. It looks like activating the hashmap at the top to depth 2, and
		 * then activating the sub-maps to depth 2 as well, works... */
		if(persistent()) {
			container.activate(putHandlersByName, 2); // depth 2 to load elements
		}
		if(logMINOR) Logger.minor(this, "Got all metadata");
		HashMap<String, Object> namesToByteArrays = new HashMap<String, Object>();
		namesToByteArrays(putHandlersByName, namesToByteArrays, container);
		if(defaultName != null) {
			Metadata meta = (Metadata) namesToByteArrays.get(defaultName);
			if(meta == null) {
				fail(new InsertException(InsertException.INVALID_URI, "Default name "+defaultName+" does not exist", null), container, context);
				return;
			}
			namesToByteArrays.put("", meta);
		} else {
			for(String name: defaultDefaultNames) {
				Metadata meta = (Metadata) namesToByteArrays.get(name);
				if(meta != null) {
					namesToByteArrays.put("", meta);
					break;
				}
			}
		}
		baseMetadata =
			Metadata.mkRedirectionManifestWithMetadata(namesToByteArrays);
		if(persistent()) {
			container.store(baseMetadata);
			container.store(this);
		}
		resolveAndStartBase(container, context);

	}

	/**
	 * Attempt to insert the base metadata and the container. If the base metadata cannot be resolved,
	 * try to resolve it: start inserts for each part that cannot be resolved, and wait for them to generate
	 * URIs that can be incorporated into the metadata. This method will then be called again, and will
	 * complete, or do more resolutions as necessary.
	 * @param container
	 * @param context
	 */
	private void resolveAndStartBase(ObjectContainer container, ClientContext context) {
		Bucket bucket = null;
		synchronized(this) {
			if(hasResolvedBase) return;
		}
		while(true) {
			try {
				if(persistent())
					container.activate(baseMetadata, Integer.MAX_VALUE);
				bucket = BucketTools.makeImmutableBucket(context.getBucketFactory(persistent()), baseMetadata.writeToByteArray());
				if(logMINOR)
					Logger.minor(this, "Metadata bucket is "+bucket.size()+" bytes long");
				break;
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
				return;
			} catch (MetadataUnresolvedException e) {
				try {
					// Start the insert for the sub-Metadata.
					// Eventually it will generate a URI and call onEncode(), which will call back here.
					if(logMINOR) Logger.minor(this, "Main metadata needs resolving: "+e);
					resolve(e, container, context);
					if(persistent())
						container.deactivate(baseMetadata, 1);
					return;
				} catch (IOException e1) {
					if(persistent())
						container.deactivate(baseMetadata, 1);
					fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
					return;
				} catch (InsertException e2) {
					if(persistent())
						container.deactivate(baseMetadata, 1);
					fail(e2, container, context);
					return;
				}
			}
		}
		if(bucket == null) return;
		synchronized(this) {
			if(hasResolvedBase) return;
			hasResolvedBase = true;
		}
		if(persistent()) {
			container.store(this);
			container.activate(elementsToPutInArchive, 2);
		}
		InsertBlock block;
		boolean isMetadata = true;
		ARCHIVE_TYPE archiveType = null;
		byte[] ckey = null;
		if(!(elementsToPutInArchive.isEmpty())) {
			// If it's just metadata don't random-encrypt it.
			ckey = forceCryptoKey;
			// There is an archive to insert.
			// We want to include the metadata.
			// We have the metadata, fortunately enough, because everything has been resolve()d.
			// So all we need to do is create the actual archive.
			OutputStream os = null;
			try {
				Bucket outputBucket = context.getBucketFactory(persistent()).makeBucket(baseMetadata.dataLength());
				// TODO: try both ? - maybe not worth it
				archiveType = ARCHIVE_TYPE.getDefault();
				os = new BufferedOutputStream(outputBucket.getOutputStream());
				String mimeType = (archiveType == ARCHIVE_TYPE.TAR ?
					createTarBucket(bucket, os, container) :
					createZipBucket(bucket, os, container));
				if(logMINOR)
					Logger.minor(this, "Archive size is "+outputBucket.size());
				bucket.free();
				if(persistent()) bucket.removeFrom(container);

				if(logMINOR) Logger.minor(this, "We are using "+archiveType);

				// Now we have to insert the Archive we have generated.

				// Can we just insert it, and not bother with a redirect to it?
				// Thereby exploiting implicit manifest support, which will pick up on .metadata??
				// We ought to be able to !!
				if(persistent()) container.activate(targetURI, 5);
				block = new InsertBlock(outputBucket, new ClientMetadata(mimeType), persistent() ? targetURI.clone() : targetURI);
				isMetadata = false;
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
				if(persistent())
					container.deactivate(baseMetadata, 1);
				return;
			} finally {
				Closer.close(os);
			}
		} else {
			if(persistent()) container.activate(targetURI, 5);
			block = new InsertBlock(bucket, null, persistent() ? targetURI.clone() : targetURI);
		}
		SingleFileInserter metadataInserter;
		try {
			// Treat it as a splitfile for purposes of determining reinserts.
			metadataInserter =
				new SingleFileInserter(this, this, block, isMetadata, ctx, realTimeFlag, (archiveType == ARCHIVE_TYPE.ZIP) , getCHKOnly, false, baseMetadata, archiveType, true, null, earlyEncode, true, persistent(), 0, 0, null, cryptoAlgorithm, ckey, -1);
			if(logMINOR) Logger.minor(this, "Inserting main metadata: "+metadataInserter+" for "+baseMetadata+" for "+this);
			if(persistent()) {
				container.activate(metadataPuttersByMetadata, 2);
				container.activate(metadataPuttersUnfetchable, 2);
			}
			this.metadataPuttersByMetadata.put(baseMetadata, metadataInserter);
			metadataPuttersUnfetchable.put(baseMetadata, metadataInserter);
			if(persistent()) {
				container.ext().store(metadataPuttersByMetadata, 2);
				container.ext().store(metadataPuttersUnfetchable, 2);
				container.deactivate(metadataPuttersByMetadata, 1);
				container.deactivate(metadataPuttersUnfetchable, 1);
				container.deactivate(baseMetadata, 1);

			}
			metadataInserter.start(container, context);
		} catch (InsertException e) {
			fail(e, container, context);
			return;
		}
		if(persistent()) {
			container.deactivate(metadataInserter, 1);
			container.deactivate(elementsToPutInArchive, 1);
		}
	}

	/**
	** OutputStream os will be close()d if this method returns successfully.
	*/
	private String createTarBucket(Bucket inputBucket, OutputStream os, ObjectContainer container) throws IOException {
		if(logMINOR) Logger.minor(this, "Create a TAR Bucket");

		TarArchiveOutputStream tarOS = new TarArchiveOutputStream(os);
		tarOS.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
		TarArchiveEntry ze;

		for(PutHandler ph : elementsToPutInArchive) {
			if(persistent()) {
				container.activate(ph, 1);
				container.activate(ph.data, 1);
			}
			if(logMINOR)
				Logger.minor(this, "Putting into tar: "+ph+" data length "+ph.data.size()+" name "+ph.targetInArchive);
			ze = new TarArchiveEntry(ph.targetInArchive);
			ze.setModTime(0);
			long size = ph.data.size();
			ze.setSize(size);
			tarOS.putArchiveEntry(ze);
			BucketTools.copyTo(ph.data, tarOS, size);
			tarOS.closeArchiveEntry();
		}

		// Add .metadata - after the rest.
		if(logMINOR)
			Logger.minor(this, "Putting metadata into tar: length is "+inputBucket.size());
		ze = new TarArchiveEntry(".metadata");
		ze.setModTime(0); // -1 = now, 0 = 1970.
		long size = inputBucket.size();
		ze.setSize(size);
		tarOS.putArchiveEntry(ze);
		BucketTools.copyTo(inputBucket, tarOS, size);

		tarOS.closeArchiveEntry();
		tarOS.close();

		return ARCHIVE_TYPE.TAR.mimeTypes[0];
	}

	private String createZipBucket(Bucket inputBucket, OutputStream os, ObjectContainer container) throws IOException {
		if(logMINOR) Logger.minor(this, "Create a ZIP Bucket");

		ZipOutputStream zos = new ZipOutputStream(os);
		ZipEntry ze;

		for(PutHandler ph : elementsToPutInArchive) {
			if(persistent()) {
				container.activate(ph, 1);
				container.activate(ph.data, 1);
			}
			ze = new ZipEntry(ph.targetInArchive);
			ze.setTime(0);
			zos.putNextEntry(ze);
			BucketTools.copyTo(ph.data, zos, ph.data.size());
			zos.closeEntry();
		}

		// Add .metadata - after the rest.
		ze = new ZipEntry(".metadata");
		ze.setTime(0); // -1 = now, 0 = 1970.
		zos.putNextEntry(ze);
		BucketTools.copyTo(inputBucket, zos, inputBucket.size());

		zos.closeEntry();
		// Both finish() and close() are necessary.
		zos.finish();

		return ARCHIVE_TYPE.ZIP.mimeTypes[0];
	}

	/**
	 * Start inserts for unresolved (too big) Metadata's. Eventually these will call back with an onEncode(),
	 * meaning they have the CHK, and we can progress to resolveAndStartBase().
	 * @param e
	 * @param container
	 * @param context
	 * @return
	 * @throws InsertException
	 * @throws IOException
	 */
	private void resolve(MetadataUnresolvedException e, ObjectContainer container, ClientContext context) throws InsertException, IOException {
		Metadata[] metas = e.mustResolve;
		if(persistent())
			container.activate(metadataPuttersByMetadata, 2);
		for(Metadata m: metas) {
			if(persistent()) container.activate(m, Integer.MAX_VALUE);
			if(logMINOR) Logger.minor(this, "Resolving "+m+" for "+this);
			synchronized(this) {
				if(metadataPuttersByMetadata.containsKey(m)) {
					if(logMINOR) Logger.minor(this, "Already started insert for "+m+" in resolve() for "+metas.length+" Metadata's");
					continue;
				}
			}
			if(m.isResolved()) {
				Logger.error(this, "Already resolved: "+m+" in resolve() - race condition???");
				if(persistent()) container.deactivate(m, 1);
				continue;
			}
			try {
				Bucket b = m.toBucket(context.getBucketFactory(persistent()));

				InsertBlock ib = new InsertBlock(b, null, persistent() ? FreenetURI.EMPTY_CHK_URI.clone() : FreenetURI.EMPTY_CHK_URI);
				// Don't random-encrypt the metadata.
				SingleFileInserter metadataInserter =
					new SingleFileInserter(this, this, ib, true, ctx, realTimeFlag, false, getCHKOnly, false, m, null, true, null, earlyEncode, false, persistent(), 0, 0, null, cryptoAlgorithm, null, -1);
				if(logMINOR) Logger.minor(this, "Inserting subsidiary metadata: "+metadataInserter+" for "+m);
				synchronized(this) {
					this.metadataPuttersByMetadata.put(m, metadataInserter);
				}
				metadataInserter.start(container, context);
				if(persistent()) {
					container.deactivate(metadataInserter, 1);
					container.deactivate(m, 1);
				}
			} catch (MetadataUnresolvedException e1) {
				resolve(e1, container, context);
				container.deactivate(m, 1);
			}
		}
		if(persistent()) {
			container.ext().store(metadataPuttersByMetadata, 2);
			container.deactivate(metadataPuttersByMetadata, 1);
		}
	}

	private void namesToByteArrays(HashMap<String, Object> putHandlersByName, HashMap<String,Object> namesToByteArrays, ObjectContainer container) {
		for(Map.Entry<String, Object> entry : putHandlersByName.entrySet()) {
			String name = entry.getKey();
			Object o = entry.getValue();
			if(o instanceof PutHandler) {
				PutHandler ph = (PutHandler) o;
				if(persistent())
					container.activate(ph, 1);
				Metadata meta = ph.metadata;
				if(ph.metadata == null)
					Logger.error(this, "Metadata for "+name+" : "+ph+" is null");
				else {
					ph.clearMetadata(container);
					if(persistent())
						container.activate(meta, Integer.MAX_VALUE);
					if(logMINOR)
						Logger.minor(this, "Putting "+name);
					namesToByteArrays.put(name, meta);
					if(logMINOR)
						Logger.minor(this, "Putting PutHandler into base metadata: "+ph+" name "+name);
				}
			} else if (o instanceof HashMap) {
				HashMap<String,Object> subMap = new HashMap<String,Object>();
				HashMap<String,Object> elements = Metadata.forceMap(o);
				if (persistent()) {
					container.activate(o, 2); // Depth 1 doesn't load the elements...
				}
				namesToByteArrays.put(name, subMap);
				if(logMINOR) {
					Logger.minor(this, "Putting hashmap into base metadata: "+name+" size "+elements.size()+" active = "+(container == null ? "null" : Boolean.toString(container.ext().isActive(o))));
					Logger.minor(this, "Putting directory: "+name);
				}
				namesToByteArrays(elements, subMap, container);
			} else
				throw new IllegalStateException();
		}
	}

	private void insertedAllFiles(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Inserted all files");
		synchronized(this) {
			insertedAllFiles = true;
			if(finished || cancelled) {
				if(logMINOR) Logger.minor(this, "Already "+(finished?"finished":"cancelled"));
				if(persistent())
					container.store(this);
				return;
			}
			if(!insertedManifest) {
				if(logMINOR) Logger.minor(this, "Haven't inserted manifest");
				if(persistent())
					container.store(this);
				return;
			}
			finished = true;
		}
		if(persistent())
			container.store(this);
		complete(container, context);
	}

	private void complete(ObjectContainer container, ClientContext context) {
		// FIXME we could remove the put handlers after inserting all files but not having finished the insert of the manifest
		// However it would complicate matters for no real gain in most cases...
		// Also doing it this way means we don't need to worry about
		if(persistent()) removePutHandlers(container, context);
		boolean deactivateCB = false;
		if(persistent()) {
			deactivateCB = !container.ext().isActive(cb);
			container.activate(cb, 1);
		}
		cb.onSuccess(this, container);
		if(deactivateCB)
			container.deactivate(cb, 1);
	}

	private void fail(InsertException e, ObjectContainer container, ClientContext context) {
		// Cancel all, then call the callback
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(persistent()) container.store(this);
		cancelAndFinish(container, context);
		if(persistent()) removePutHandlers(container, context);

		if(persistent())
			container.activate(cb, 1);
		cb.onFailure(e, this, container);
	}

	private void removePutHandlers(ObjectContainer container, ClientContext context) {
		container.activate(putHandlersByName, 2);
		container.activate(runningPutHandlers, 2);
		container.activate(putHandlersWaitingForMetadata, 2);
		container.activate(waitingForBlockSets, 2);
		container.activate(putHandlersWaitingForFetchable, 2);
		container.activate(elementsToPutInArchive, 2);
		removePutHandlersByName(container, context, putHandlersByName);
		putHandlersByName = null;

		if(!runningPutHandlers.isEmpty()) {
			Logger.error(this, "Running put handlers not part of putHandlersByName: "+runningPutHandlers.size()+" in removePutHandlers() on "+this, new Exception("error"));
			PutHandler[] handlers = runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
			for(PutHandler handler : handlers) {
				container.activate(handler, 1);
				Logger.error(this, "Still running, but not in putHandlersByName: "+handler);
				handler.cancel();
				handler.removeFrom(container, context);
			}
			runningPutHandlers.clear();
		}
		if(!putHandlersWaitingForMetadata.isEmpty()) {
			Logger.error(this, "Put handlers waiting for metadata, not part of putHandlersByName: "+putHandlersWaitingForMetadata.size()+" in removePutHandlers() on "+this, new Exception("error"));
			PutHandler[] handlers = putHandlersWaitingForMetadata.toArray(new PutHandler[putHandlersWaitingForMetadata.size()]);
			for(PutHandler handler : handlers) {
				container.activate(handler, 1);
				Logger.error(this, "Still waiting for metadata, but not in putHandlersByName: "+handler);
				handler.cancel();
				handler.removeFrom(container, context);
			}
			putHandlersWaitingForMetadata.clear();
		}
		if(!waitingForBlockSets.isEmpty()) {
			Logger.error(this, "Put handlers waiting for block sets, not part of putHandlersByName: "+waitingForBlockSets.size()+" in removePutHandlers() on "+this, new Exception("error"));
			PutHandler[] handlers = waitingForBlockSets.toArray(new PutHandler[waitingForBlockSets.size()]);
			for(PutHandler handler : handlers) {
				container.activate(handler, 1);
				Logger.error(this, "Still waiting for block set, but not in putHandlersByName: "+handler);
				handler.cancel();
				handler.removeFrom(container, context);
			}
			waitingForBlockSets.clear();
		}
		if(!putHandlersWaitingForFetchable.isEmpty()) {
			Logger.error(this, "Put handlers waiting for fetchable, not part of putHandlersByName: "+putHandlersWaitingForFetchable.size()+" in removePutHandlers() on "+this, new Exception("error"));
			PutHandler[] handlers = putHandlersWaitingForFetchable.toArray(new PutHandler[putHandlersWaitingForFetchable.size()]);
			for(PutHandler handler : handlers) {
				container.activate(handler, 1);
				Logger.error(this, "Still waiting for fetchable, but not in putHandlersByName: "+handler);
				handler.cancel();
				handler.removeFrom(container, context);
			}
			putHandlersWaitingForFetchable.clear();
		}
		if(!elementsToPutInArchive.isEmpty()) {
			Logger.error(this, "Elements to put in archive, not part of putHandlersByName: "+elementsToPutInArchive.size()+" in removePutHandlers() on "+this, new Exception("error"));
			PutHandler[] handlers = elementsToPutInArchive.toArray(new PutHandler[elementsToPutInArchive.size()]);
			for(PutHandler handler : handlers) {
				container.activate(handler, 1);
				Logger.error(this, "To put in archive, but not in putHandlersByName: "+handler);
				handler.removeFrom(container, context);
			}
			elementsToPutInArchive.clear();
		}

		container.delete(runningPutHandlers);
		container.delete(putHandlersWaitingForMetadata);
		container.delete(waitingForBlockSets);
		container.delete(putHandlersWaitingForFetchable);
		container.delete(elementsToPutInArchive);
		runningPutHandlers = null;
		putHandlersWaitingForMetadata = null;
		waitingForBlockSets = null;
		putHandlersWaitingForFetchable = null;
		elementsToPutInArchive = null;
		container.store(this);
	}

	/**
	 * Remove all PutHandler's from the given putHandlersByName sub-map.
	 * Remove the PutHandler's themselves also, remove them from and complain about
	 * runningPutHandlers, putHandlersWaitingForMetadata, waitingForBlockSets,
	 * putHandlersWaitingForFetchable, which must have been activated by the caller.
	 * @param container
	 * @param putHandlersByName
	 */
	private void removePutHandlersByName(ObjectContainer container, ClientContext context, HashMap<String, Object> putHandlersByName) {
		if(logMINOR) Logger.minor(this, "removePutHandlersByName on "+this+" : map size = "+putHandlersByName.size());
		for(Map.Entry<String, Object> entry : putHandlersByName.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if(value instanceof PutHandler) {
				PutHandler handler = (PutHandler) value;
				container.activate(handler, 1);
				if(runningPutHandlers != null && runningPutHandlers.remove(handler))
					container.ext().store(runningPutHandlers, 2);
				if(putHandlersWaitingForMetadata != null && putHandlersWaitingForMetadata.remove(handler))
					container.ext().store(putHandlersWaitingForMetadata, 2);
				if(waitingForBlockSets != null && waitingForBlockSets.remove(handler))
					container.ext().store(waitingForBlockSets, 2);
				if(putHandlersWaitingForMetadata != null && putHandlersWaitingForFetchable.remove(handler))
					container.ext().store(putHandlersWaitingForFetchable, 2);
				if(elementsToPutInArchive != null && elementsToPutInArchive.remove(handler))
					container.ext().store(elementsToPutInArchive, 2);
				handler.removeFrom(container, context);
			} else {
				HashMap<String, Object> subMap = Metadata.forceMap(value);
				container.activate(subMap, 2);
				removePutHandlersByName(container, context, subMap);
			}
			container.delete(key);
		}
		putHandlersByName.clear();
		container.delete(putHandlersByName);
	}

	/**
	 * Cancel all running inserters.
	 */
	private void cancelAndFinish(ObjectContainer container, ClientContext context) {
		PutHandler[] running;
		boolean persistent = persistent();
		if(persistent)
			container.activate(runningPutHandlers, 2);
		synchronized(this) {
			running = runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
		}

		if(logMINOR) Logger.minor(this, "PutHandler's to cancel: "+running.length);
		for(PutHandler putter : running) {
			boolean active = true;
			if(persistent) {
				active = container.ext().isActive(putter);
				if(!active) container.activate(putter, 1);
			}
			putter.cancel(container, context);
			if(!active) container.deactivate(putter, 1);
			if(persistent) container.activate(this, 1);
		}

		ClientPutState[] runningMeta;
		if(persistent())
			container.activate(metadataPuttersByMetadata, 2);
		synchronized(this) {
			runningMeta = metadataPuttersByMetadata.values().toArray(new ClientPutState[metadataPuttersByMetadata.size()]);
		}

		if(logMINOR) Logger.minor(this, "Metadata putters to cancel: "+runningMeta.length);
		for(ClientPutState putter : runningMeta) {
			boolean active = true;
			if(persistent) {
				active = container.ext().isActive(putter);
				if(!active) container.activate(putter, 1);
			}
			putter.cancel(container, context);
			if(!active) container.deactivate(putter, 1);
			if(persistent) container.activate(this, 1);
		}

	}

	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			if(super.cancel()) return;
		}
		if(persistent())
			container.store(this);
		fail(new InsertException(InsertException.CANCELLED), container, context);
	}

	@Override
	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		Metadata token = (Metadata) state.getToken();
		if(persistent()) {
			// See comments at the beginning of the file re HashMap activation.
			// We MUST activate token first, and we MUST activate the maps to depth 2.
			// And everything that isn't already active will end up on a long chain off bucket 0, so this isn't terribly efficient!
			// Fortunately the chances of having a lot of metadata putters are quite low.
			// This is not necessarily the case for running* ...
			container.activate(token, 1);
			container.activate(metadataPuttersByMetadata, 2);
		}
		boolean fin = false;
		ClientPutState oldState = null;
		synchronized(this) {
			oldState = metadataPuttersByMetadata.remove(token);
			if(oldState != null) {
				if(persistent())
					container.activate(metadataPuttersUnfetchable, 2);
				if(metadataPuttersUnfetchable.remove(token) != null) {
					if(persistent())
						container.ext().store(metadataPuttersUnfetchable, 2);
				}
			} else {
				if(logMINOR) Logger.minor(this, "Did not remove metadata putter "+state+" for "+token+" because not present");
			}
			if(!metadataPuttersByMetadata.isEmpty()) {
				if(logMINOR) {
					Logger.minor(this, "Still running metadata putters: "+metadataPuttersByMetadata.size());
					// FIXME simplify when confident that it works.
					// We do want to show what's still running though.
					for(Map.Entry<Metadata,ClientPutState> entry : metadataPuttersByMetadata.entrySet()) {
						boolean active = true;
						boolean metaActive = true;
						ClientPutState s = entry.getValue();
						Metadata key = entry.getKey();
						if(persistent()) {
							active = container.ext().isActive(s);
							if(!active) container.activate(s, 1);
							metaActive = container.ext().isActive(key);
							if(!active) container.activate(metaActive, 1);
						}
						Logger.minor(this, "Still waiting for "+s+" for "+key);
						if(persistent()) Logger.minor(this, "Key id is "+container.ext().getID(key));
						if(key == token)
							Logger.error(this, "MATCHED, yet didn't find it earlier?!");
						if(key.equals(token))
							Logger.error(this, "MATCHED ON equals(), yet didn't find it earlier and not == ?!");
						if(!active) container.deactivate(s, 1);
						if(!metaActive) container.deactivate(key, 1);
					}
				}
			} else {
				Logger.minor(this, "Inserted manifest successfully on "+this+" : "+state);
				insertedManifest = true;
				if(finished) {
					if(logMINOR) Logger.minor(this, "Already finished");
					if(persistent())
						container.store(this);
				} else if(!insertedAllFiles) {
					if(logMINOR) Logger.minor(this, "Not inserted all files");
					if(persistent())
						container.store(this);
				} else {
					finished = true;
					if(persistent()) container.store(this);
					fin = true;
				}
			}
		}
		if(persistent()) {
			if(token != baseMetadata)
				token.removeFrom(container);
			container.ext().store(metadataPuttersByMetadata, 2);
			container.deactivate(metadataPuttersByMetadata, 1);
			state.removeFrom(container, context);
			if(oldState != state && oldState != null) {
				container.activate(oldState, 1);
				oldState.removeFrom(container, context);
			}
		}
		if(fin)
			complete(container, context);
	}

	@Override
	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(metadataPuttersByMetadata, 2);
		}
		ClientPutState oldState = null;
		Metadata token = (Metadata) state.getToken();
		synchronized(this) {
			if(persistent()) container.activate(token, 1);
			oldState = metadataPuttersByMetadata.remove(token);
			if(oldState != null) {
				if(persistent())
					container.activate(metadataPuttersUnfetchable, 2);
				if(metadataPuttersUnfetchable.remove(token) != null) {
					if(persistent())
						container.ext().store(metadataPuttersUnfetchable, 2);
				}
			}
		}
		if(token != baseMetadata)
			token.removeFrom(container);
		if(persistent()) {
			container.ext().store(metadataPuttersByMetadata, 2);
			container.deactivate(metadataPuttersByMetadata, 1);
			state.removeFrom(container, context);
			if(oldState != state && oldState != null) {
				container.activate(oldState, 1);
				oldState.removeFrom(container, context);
			}
		}
		fail(e, container, context);
	}

	@Override
	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(state.getToken() == baseMetadata) {
			this.finalURI = key.getURI();
			if(logMINOR) Logger.minor(this, "Got metadata key: "+finalURI);
			if(persistent())
				container.activate(cb, 1);
			cb.onGeneratedURI(persistent() ? finalURI.clone() : finalURI, this, container);
			if(persistent())
				container.deactivate(cb, 1);
			if(persistent())
				container.store(this);
		} else {
			// It's a sub-Metadata
			Metadata m = (Metadata) state.getToken();
			if(persistent())
				container.activate(m, 2);
			m.resolve(key.getURI());
			if(persistent())
				container.store(m);
			if(logMINOR) Logger.minor(this, "Resolved "+m+" : "+key.getURI());
			resolveAndStartBase(container, context);
		}
	}

	@Override
	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		Metadata m = (Metadata) oldState.getToken();
		if(persistent()) {
			container.activate(m, Integer.MAX_VALUE);
			container.activate(metadataPuttersUnfetchable, 2);
			container.activate(metadataPuttersByMetadata, 2);
		}
		synchronized(this) {
			ClientPutState prevState = metadataPuttersByMetadata.get(m);
			if(prevState != null) {
				if(prevState != oldState) {
					if(logMINOR) Logger.minor(this, "Ignoring transition in "+this+" for metadata putter: "+oldState+" -> "+newState+" because current for "+m+" is "+prevState);
					if(persistent()) {
						container.deactivate(metadataPuttersUnfetchable, 1);
						container.deactivate(metadataPuttersByMetadata, 1);
					}
					return;
				}
				if(persistent()) container.store(newState);
				metadataPuttersByMetadata.put(m, newState);
				if(persistent()) container.ext().store(metadataPuttersByMetadata, 2);
				if(logMINOR) Logger.minor(this, "Metadata putter transition: "+oldState+" -> "+newState);
				if(metadataPuttersUnfetchable.containsKey(m)) {
					metadataPuttersUnfetchable.put(m, newState);
					if(persistent()) container.ext().store(metadataPuttersUnfetchable, 2);
					if(logMINOR) Logger.minor(this, "Unfetchable metadata putter transition: "+oldState+" -> "+newState);
				}
				if(logMINOR) Logger.minor(this, "Transition: "+oldState+" -> "+newState);
			} else {
				Logger.error(this, "onTransition() but metadataPuttersByMetadata does not contain metadata tag "+m+" for "+oldState+" should -> "+newState);
			}
		}

		if(persistent()) {
			container.deactivate(m, 1);
			container.deactivate(metadataPuttersUnfetchable, 2);
			container.deactivate(metadataPuttersByMetadata, 2);
		}
	}

	@Override
	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	/** The number of blocks that will be needed to fetch the data. We put this in the top block metadata. */
	protected int minSuccessFetchBlocks;
	
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
	public void notifyClients(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(ctx, 1);
			container.activate(ctx.eventProducer, 1);
		}
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.minSuccessFetchBlocks, this.blockSetFinalized), container, context);
	}

	@Override
	public int getMinSuccessFetchBlocks() {
		return minSuccessFetchBlocks;
	}
	
	@Override
	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			this.metadataBlockSetFinalized = true;
			if(persistent())
				container.activate(waitingForBlockSets, 2);
			if(!waitingForBlockSets.isEmpty()) {
				if(persistent()) {
					container.store(this);
					container.deactivate(waitingForBlockSets, 1);
				}
				return;
			}
		}
		this.blockSetFinalized(container, context);
		if(persistent()) {
			container.store(this);
			container.deactivate(waitingForBlockSets, 1);
		}
	}

	@Override
	public void blockSetFinalized(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(!metadataBlockSetFinalized) return;
			if(persistent())
				container.activate(waitingForBlockSets, 2);
			if(waitingForBlockSets.isEmpty()) {
				if(persistent())
					container.deactivate(waitingForBlockSets, 1);
				return;
			}
		}
		if(persistent())
			container.deactivate(waitingForBlockSets, 1);
		super.blockSetFinalized(container, context);
		if(persistent())
			container.store(this);
	}

	/**
	 * Convert a HashMap of name -> bucket to a HashSet of ManifestEntry's.
	 * All are to have mimeOverride=null, i.e. we use the auto-detected mime type
	 * from the filename.
	 */
	public static HashMap<String, Object> bucketsByNameToManifestEntries(HashMap<String,Object> bucketsByName) {
		HashMap<String,Object> manifestEntries = new HashMap<String,Object>();
		for(Map.Entry<String,Object> entry: bucketsByName.entrySet()) {
			String name = entry.getKey();
			Object o = entry.getValue();
			if(o instanceof ManifestElement) {
				manifestEntries.put(name, o);
			} else if(o instanceof Bucket) {
				Bucket data = (Bucket) o;
				manifestEntries.put(name, new ManifestElement(name, data, null,data.size()));
			} else if(o instanceof HashMap) {
				manifestEntries.put(name, bucketsByNameToManifestEntries(Metadata.forceMap(o)));
			} else
				throw new IllegalArgumentException(String.valueOf(o));
		}
		return manifestEntries;
	}

	/**
	 * Convert a hierarchy of HashMap's of ManifestEntries into a series of
	 * ManifestElement's, each of which has a full path.
	 */
	public static ManifestElement[] flatten(HashMap<String,Object> manifestElements) {
		List<ManifestElement> v = new ArrayList<ManifestElement>();
		flatten(manifestElements, v, "");
		return v.toArray(new ManifestElement[v.size()]);
	}

	public static void flatten(HashMap<String,Object> manifestElements, List<ManifestElement> v, String prefix) {
		for(Map.Entry<String,Object> entry: manifestElements.entrySet()) {
			String name = entry.getKey();
			String fullName = prefix.length() == 0 ? name : prefix+ '/' +name;
			Object o = entry.getValue();
			if(o instanceof HashMap) {
				flatten(Metadata.forceMap(o), v, fullName);
			} else if(o instanceof ManifestElement) {
				ManifestElement me = (ManifestElement) o;
				v.add(new ManifestElement(me, fullName));
			} else
				throw new IllegalStateException(String.valueOf(o));
		}
	}

	/**
	 * Opposite of flatten(...).
	 * Note that this can throw a ClassCastException if the vector passed in is
	 * bogus (has files pretending to be directories).
	 */
	public static <T> HashMap<String, Object> unflatten(List<ManifestElement> v) {
		HashMap<String, Object> manifestElements = new HashMap<String, Object>();
		for(ManifestElement oldElement : v) {
			add(oldElement, oldElement.getName(), manifestElements);
		}
		return manifestElements;
	}

	private static void add(ManifestElement e, String namePart, Map<String, Object> target) {
		int idx = namePart.indexOf('/');
		if(idx < 0) {
			target.put(namePart, new ManifestElement(e, namePart));
		} else {
			String before = namePart.substring(0, idx);
			String after = namePart.substring(idx+1);
			HashMap<String, Object> hm = Metadata.forceMap(target.get(before));
			if(hm == null) {
				hm = new HashMap<String, Object>();
				target.put(before.intern(), hm);
			}
			add(e, after, hm);
		}
	}

	public int countFiles() {
		return numberOfFiles;
	}

	public long totalSize() {
		return totalSize;
	}

	@Override
	public void onMajorProgress(ObjectContainer container) {
		boolean deactivate = false;
		if(persistent()) {
			deactivate = !container.ext().isActive(cb);
			if(deactivate) container.activate(cb, 1);
		}
		cb.onMajorProgress(container);
		if(deactivate) container.deactivate(cb, 1);
	}

	protected void onFetchable(PutHandler handler, ObjectContainer container) {
		if(persistent()) {
			container.activate(putHandlersWaitingForFetchable, 2);
			container.activate(metadataPuttersUnfetchable, 2);
		}
		if(checkFetchable(handler)) {
			if(persistent()) {
				container.ext().store(putHandlersWaitingForMetadata, 2);
				container.store(this);
				container.deactivate(putHandlersWaitingForFetchable, 1);
				container.deactivate(metadataPuttersUnfetchable, 1);
				container.activate(cb, 1);
			}
			cb.onFetchable(this, container);
			if(persistent())
				container.deactivate(cb, 1);
		} else {
			if(persistent()) {
				container.deactivate(putHandlersWaitingForFetchable, 1);
				container.deactivate(metadataPuttersUnfetchable, 1);
			}
		}
	}

	private synchronized boolean checkFetchable(PutHandler handler) {
		putHandlersWaitingForFetchable.remove(handler);
		if(fetchable) return false;
		if(!putHandlersWaitingForFetchable.isEmpty()) return false;
		if(!hasResolvedBase) return false;
		if(!metadataPuttersUnfetchable.isEmpty()) return false;
		fetchable = true;
		return true;
	}

	@Override
	public void onFetchable(ClientPutState state, ObjectContainer container) {
		Metadata m = (Metadata) state.getToken();
		if(persistent()) {
			container.activate(m, Integer.MAX_VALUE);
			container.activate(metadataPuttersUnfetchable, 2);
			container.activate(putHandlersWaitingForFetchable, 2);
		}
		if(checkFetchable(m)) {
			if(persistent()) {
				container.ext().store(metadataPuttersUnfetchable, 2);
				container.store(this);
				container.activate(cb, 1);
			}
			cb.onFetchable(this, container);
			if(persistent())
				container.deactivate(cb, 1);
		}
		if(persistent()) {
			container.deactivate(metadataPuttersUnfetchable, 1);
			container.deactivate(putHandlersWaitingForFetchable, 1);
		}
	}

	private synchronized boolean checkFetchable(Metadata m) {
		metadataPuttersUnfetchable.remove(m);
		if(!metadataPuttersUnfetchable.isEmpty()) return false;
		if(fetchable) return false;
		if(!putHandlersWaitingForFetchable.isEmpty()) return false;
		fetchable = true;
		return true;
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		if(putHandlersByName != null) {
			Logger.error(this, "Put handlers list still present in removeFrom() on "+this);
			removePutHandlers(container, context);
		}
		if(finalURI != null) {
			container.activate(finalURI, 5);
			finalURI.removeFrom(container);
		}
		container.activate(targetURI, 5);
		targetURI.removeFrom(container);
		// This is passed in. We should not remove it, because the caller (ClientPutDir) should remove it.
//		container.activate(ctx, 1);
//		ctx.removeFrom(container);
		container.activate(metadataPuttersByMetadata, 2);
		container.activate(metadataPuttersUnfetchable, 2);
		ArrayList<Metadata> metas = null;
		if(!metadataPuttersByMetadata.isEmpty()) {
			Logger.error(this, "Metadata putters by metadata not empty in removeFrom() on "+this);
			for(Map.Entry<Metadata, ClientPutState> entry : metadataPuttersByMetadata.entrySet()) {
				Metadata meta = entry.getKey();
				container.activate(meta, 1);
				ClientPutState sfi = entry.getValue();
				container.activate(sfi, 1);
				metadataPuttersUnfetchable.remove(meta);
				Logger.error(this, "Metadata putters not empty: "+sfi+" for "+this);
				sfi.cancel(container, context);
				sfi.removeFrom(container, context);
				if(metas == null) metas = new ArrayList<Metadata>();
				metas.add(meta);
			}
		}
		if(!metadataPuttersUnfetchable.isEmpty()) {
			Logger.error(this, "Metadata putters unfetchable by metadata not empty in removeFrom() on "+this);
			for(Map.Entry<Metadata, ClientPutState> entry : metadataPuttersByMetadata.entrySet()) {
				Metadata meta = entry.getKey();
				container.activate(meta, 1);
				ClientPutState sfi = entry.getValue();
				container.activate(sfi, 1);
				metadataPuttersUnfetchable.remove(meta);
				Logger.error(this, "Metadata putters unfetchable not empty: "+sfi+" for "+this);
				sfi.cancel(container, context);
				sfi.removeFrom(container, context);
			}
		}
		if(metas != null) {
			for(Metadata meta : metas) {
				if(meta == baseMetadata) continue;
				container.activate(meta, 1);
				meta.removeFrom(container);
			}
		}
		metadataPuttersByMetadata.clear();
		metadataPuttersUnfetchable.clear();
		container.delete(metadataPuttersByMetadata);
		container.delete(metadataPuttersUnfetchable);
		if(baseMetadata != null) {
			container.activate(baseMetadata, 1);
			baseMetadata.removeFrom(container);
		}
		container.activate(runGotAllMetadata, 1);
		container.delete(runGotAllMetadata);
		super.removeFrom(container, context);
	}

	public void objectOnUpdate(ObjectContainer container) {
		if(logDEBUG) Logger.debug(this, "Updating "+this+" activated="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("debug"));
	}

	@Override
	public boolean objectCanNew(ObjectContainer container) {
		if(finished) {
			Logger.error(this, "Storing "+this+" when already finished!", new Exception("error"));
			return false;
		}
		if(logDEBUG) Logger.debug(this, "Storing "+this+" activated="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("debug"));
		return true;
	}

	// compose helper stuff

	protected final ClientMetadata guessMime(String name, ManifestElement me) {
		String mimeType = me.mimeOverride;
		if(mimeType == null)
			mimeType = DefaultMIMETypes.guessMIMEType(name, true);
		ClientMetadata cm;
		if(mimeType == null || mimeType.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE))
			cm = null;
		else
			cm = new ClientMetadata(mimeType);
		return cm;
	}

	protected final void addRedirectNoMime(String name, ManifestElement me, HashMap<String, Object> putHandlersByName2) {
		addRedirect(name, me, null, putHandlersByName2);
	}

	protected final void addRedirect(String name, ManifestElement me, HashMap<String, Object> putHandlersByName2) {
		addRedirect(name, me, guessMime(name, me), putHandlersByName2);
	}

	protected final void addRedirect(String name, ManifestElement me, ClientMetadata cm, HashMap<String, Object> putHandlersByName2) {
		PutHandler ph;
		Bucket data = me.data;
		if(me.targetURI != null) {
			ph = new PutHandler(this, name, me.targetURI, cm, persistent());
			// Just a placeholder, don't actually run it
		} else {
			ph = new PutHandler(this, name, data, cm, getCHKOnly, persistent());
			runningPutHandlers.add(ph);
			putHandlersWaitingForMetadata.add(ph);
			putHandlersWaitingForFetchable.add(ph);
			if(logMINOR)
				Logger.minor(this, "Inserting separately as PutHandler: "+name+" : "+ph+" persistent="+ph.persistent()+":"+ph.persistent+" "+persistent());
			numberOfFiles++;
			totalSize += data.size();
		}
		putHandlersByName2.put(name, ph);
	}

	@Override
	protected void innerToNetwork(ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void onMetadata(Bucket meta, ClientPutState state,
			ObjectContainer container, ClientContext context) {
		throw new UnsupportedOperationException();
	}

}

