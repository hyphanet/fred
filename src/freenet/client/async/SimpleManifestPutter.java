package freenet.client.async;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LoggerPriority;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.NativeThread;

public class SimpleManifestPutter extends BaseClientPutter implements PutCompletionCallback {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LoggerPriority.MINOR, this);
				logDEBUG = Logger.shouldLog(LoggerPriority.DEBUG, this);
			}
		});
	}

	// Only implements PutCompletionCallback for the final metadata insert
	private class PutHandler extends BaseClientPutter implements PutCompletionCallback {

		protected PutHandler(final SimpleManifestPutter smp, String name, Bucket data, ClientMetadata cm, boolean getCHKOnly, boolean persistent) {
			super(smp.priorityClass, smp.client);
			this.persistent = persistent;
			this.cm = cm;
			this.data = data;
			InsertBlock block =
				new InsertBlock(data, cm, persistent() ? FreenetURI.EMPTY_CHK_URI.clone() : FreenetURI.EMPTY_CHK_URI);
			this.origSFI =
				new SingleFileInserter(this, this, block, false, ctx, false, getCHKOnly, true, null, null, false, null, earlyEncode, false, persistent);
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
			if(Logger.shouldLog(LoggerPriority.MINOR, this))
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
				if(putHandlersWaitingForMetadata.contains(this)) {
					putHandlersWaitingForMetadata.remove(this);
					container.ext().store(putHandlersWaitingForMetadata, 2);
					Logger.error(this, "PutHandler was in waitingForMetadata in onSuccess() on "+this+" for "+SimpleManifestPutter.this);
				}

				if(persistent) {
					container.deactivate(putHandlersWaitingForMetadata, 1);
					container.activate(waitingForBlockSets, 2);
				}
				if(waitingForBlockSets.contains(this)) {
					waitingForBlockSets.remove(this);
					container.store(waitingForBlockSets);
					Logger.error(this, "PutHandler was in waitingForBlockSets in onSuccess() on "+this+" for "+SimpleManifestPutter.this);
				}
				if(persistent) {
					container.deactivate(waitingForBlockSets, 1);
					container.deactivate(putHandlersWaitingForFetchable, 1);
					container.activate(putHandlersWaitingForFetchable, 2);
				}
				if(putHandlersWaitingForFetchable.contains(this)) {
					putHandlersWaitingForFetchable.remove(this);
					container.ext().store(putHandlersWaitingForFetchable, 2);
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
		public void addBlock(ObjectContainer container) {
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
			}
			SimpleManifestPutter.this.addBlock(container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		@Override
		public void addBlocks(int num, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.addBlocks(num, container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		@Override
		public void completedBlock(boolean dontNotify, ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.completedBlock(dontNotify, container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		@Override
		public void failedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.failedBlock(container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		@Override
		public void fatallyFailedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.fatallyFailedBlock(container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		@Override
		public void addMustSucceedBlocks(int blocks, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.addMustSucceedBlocks(blocks, container);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

		@Override
		public void notifyClients(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.notifyClients(container, context);
			if(persistent)
				container.deactivate(SimpleManifestPutter.this, 1);
		}

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

	public SimpleManifestPutter(ClientPutCallback cb,
			HashMap<String, Object> manifestElements, short prioClass, FreenetURI target,
			String defaultName, InsertContext ctx, boolean getCHKOnly, RequestClient clientContext, boolean earlyEncode) {
		super(prioClass, clientContext);
		this.defaultName = defaultName;
		if(client.persistent())
			this.targetURI = target.clone();
		else
			this.targetURI = target;
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
		makePutHandlers(manifestElements, putHandlersByName, client.persistent());
		checkZips();
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
		makePutHandlers(manifestElements, putHandlersByName, "/", persistent);
	}

	private void makePutHandlers(HashMap<String, Object> manifestElements, HashMap<String,Object> putHandlersByName, String ZipPrefix, boolean persistent) {
		Iterator<String> it = manifestElements.keySet().iterator();
		while(it.hasNext()) {
			String name = it.next();
			Object o = manifestElements.get(name);
			if (o instanceof HashMap) {
				HashMap<String,Object> subMap = new HashMap<String,Object>();
				putHandlersByName.put(name, subMap);
				makePutHandlers(Metadata.forceMap(o), subMap, ZipPrefix+name+ '/', persistent);
				if(Logger.shouldLog(LoggerPriority.DEBUG, this))
					Logger.debug(this, "Sub map for "+name+" : "+subMap.size()+" elements from "+((HashMap)o).size());
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

	private final DBJob runGotAllMetadata = new DBJob() {

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
			for(int j=0;j<defaultDefaultNames.length;j++) {
				String name = defaultDefaultNames[j];
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
		boolean insertAsArchiveManifest = false;
		ARCHIVE_TYPE archiveType = null;
		if(!(elementsToPutInArchive.isEmpty())) {
			// There is an archive to insert.
			// We want to include the metadata.
			// We have the metadata, fortunately enough, because everything has been resolve()d.
			// So all we need to do is create the actual archive.
			try {
				Bucket outputBucket = context.getBucketFactory(persistent()).makeBucket(baseMetadata.dataLength());
				// TODO: try both ? - maybe not worth it
				archiveType = ARCHIVE_TYPE.getDefault();
				String mimeType = (archiveType == ARCHIVE_TYPE.TAR ?
					createTarBucket(bucket, outputBucket, container) :
					createZipBucket(bucket, outputBucket, container));
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
				insertAsArchiveManifest = true;
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container, context);
				if(persistent())
					container.deactivate(baseMetadata, 1);
				return;
			}
		} else
			block = new InsertBlock(bucket, null, persistent() ? targetURI.clone() : targetURI);
		SingleFileInserter metadataInserter;
		try {
			// Treat it as a splitfile for purposes of determining reinserts.
			metadataInserter =
				new SingleFileInserter(this, this, block, isMetadata, ctx, (archiveType == ARCHIVE_TYPE.ZIP) , getCHKOnly, false, baseMetadata, archiveType, true, null, earlyEncode, true, persistent());
			if(logMINOR) Logger.minor(this, "Inserting main metadata: "+metadataInserter+" for "+baseMetadata);
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
			metadataInserter.start(null, container, context);
		} catch (InsertException e) {
			fail(e, container, context);
			return;
		}
		if(persistent()) {
			container.deactivate(metadataInserter, 1);
			container.deactivate(elementsToPutInArchive, 1);
		}
	}

	private String createTarBucket(Bucket inputBucket, Bucket outputBucket, ObjectContainer container) throws IOException {
		if(logMINOR) Logger.minor(this, "Create a TAR Bucket");

		OutputStream os = new BufferedOutputStream(outputBucket.getOutputStream());
		TarOutputStream tarOS = new TarOutputStream(os);
		tarOS.setLongFileMode(TarOutputStream.LONGFILE_GNU);
		TarEntry ze;

		for(PutHandler ph : elementsToPutInArchive) {
			if(persistent()) {
				container.activate(ph, 1);
				container.activate(ph.data, 1);
			}
			if(logMINOR)
				Logger.minor(this, "Putting into tar: "+ph+" data length "+ph.data.size()+" name "+ph.targetInArchive);
			ze = new TarEntry(ph.targetInArchive);
			ze.setModTime(0);
			long size = ph.data.size();
			ze.setSize(size);
			tarOS.putNextEntry(ze);
			BucketTools.copyTo(ph.data, tarOS, size);
			tarOS.closeEntry();
		}

		// Add .metadata - after the rest.
		if(logMINOR)
			Logger.minor(this, "Putting metadata into tar: length is "+inputBucket.size());
		ze = new TarEntry(".metadata");
		ze.setModTime(0); // -1 = now, 0 = 1970.
		long size = inputBucket.size();
		ze.setSize(size);
		tarOS.putNextEntry(ze);
		BucketTools.copyTo(inputBucket, tarOS, size);

		tarOS.closeEntry();
		// Both finish() and close() are necessary.
		tarOS.finish();
		tarOS.flush();
		tarOS.close();

		if(logMINOR)
			Logger.minor(this, "Archive size is "+outputBucket.size());

		return ARCHIVE_TYPE.TAR.mimeTypes[0];
	}

	private String createZipBucket(Bucket inputBucket, Bucket outputBucket, ObjectContainer container) throws IOException {
		if(logMINOR) Logger.minor(this, "Create a ZIP Bucket");

		OutputStream os = new BufferedOutputStream(outputBucket.getOutputStream());
		ZipOutputStream zos = new ZipOutputStream(os);
		ZipEntry ze;

		for(Iterator<PutHandler> i = elementsToPutInArchive.iterator(); i.hasNext();) {
			PutHandler ph = i.next();
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
		zos.flush();
		zos.close();

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
		for(int i=0;i<metas.length;i++) {
			Metadata m = metas[i];
			if(persistent()) container.activate(m, 100);
			if(logMINOR) Logger.minor(this, "Resolving "+m);
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
				SingleFileInserter metadataInserter =
					new SingleFileInserter(this, this, ib, true, ctx, false, getCHKOnly, false, m, null, true, null, earlyEncode, false, persistent());
				if(logMINOR) Logger.minor(this, "Inserting subsidiary metadata: "+metadataInserter+" for "+m);
				synchronized(this) {
					this.metadataPuttersByMetadata.put(m, metadataInserter);
				}
				metadataInserter.start(null, container, context);
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
		Iterator<String> i = putHandlersByName.keySet().iterator();
		while(i.hasNext()) {
			String name = i.next();
			Object o = putHandlersByName.get(name);
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
						container.activate(meta, 100);
					if(logMINOR)
						Logger.minor(this, "Putting "+name);
					namesToByteArrays.put(name, meta);
					if(logMINOR)
						Logger.minor(this, "Putting PutHandler into base metadata: "+ph+" name "+name);
				}
			} else if (o instanceof HashMap) {
				HashMap<String,Object> subMap = new HashMap<String,Object>();
				if (persistent()) {
					container.activate(o, 2); // Depth 1 doesn't load the elements...
				}
				namesToByteArrays.put(name, subMap);
				if(logMINOR) {
					Logger.minor(this, "Putting hashmap into base metadata: "+name+" size "+((HashMap)o).size()+" active = "+(container == null ? "null" : Boolean.toString(container.ext().isActive(o))));
					Logger.minor(this, "Putting directory: "+name);
				}
				namesToByteArrays(Metadata.forceMap(o), subMap, container);
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
				if(runningPutHandlers.remove(handler))
					container.ext().store(runningPutHandlers, 2);
				if(putHandlersWaitingForMetadata.remove(handler))
					container.ext().store(putHandlersWaitingForMetadata, 2);
				if(waitingForBlockSets.remove(handler))
					container.ext().store(waitingForBlockSets, 2);
				if(putHandlersWaitingForFetchable.remove(handler))
					container.ext().store(putHandlersWaitingForFetchable, 2);
				if(elementsToPutInArchive.remove(handler))
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

	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(metadataPuttersByMetadata, 2);
		}
		boolean fin = false;
		ClientPutState oldState = null;
		Metadata token = (Metadata) state.getToken();
		synchronized(this) {
			if(persistent()) container.activate(token, 1);
			boolean present = metadataPuttersByMetadata.containsKey(token);
			if(present) {
				oldState = metadataPuttersByMetadata.remove(token);
				if(persistent())
					container.activate(metadataPuttersUnfetchable, 2);
				if(metadataPuttersUnfetchable.containsKey(token)) {
					metadataPuttersUnfetchable.remove(token);
					if(persistent())
						container.ext().store(metadataPuttersUnfetchable, 2);
				}
			}
			if(!metadataPuttersByMetadata.isEmpty()) {
				if(logMINOR) Logger.minor(this, "Still running metadata putters: "+metadataPuttersByMetadata.size());
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
		if(fin)
			complete(container, context);
	}

	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(metadataPuttersByMetadata, 2);
		}
		ClientPutState oldState = null;
		Metadata token = (Metadata) state.getToken();
		synchronized(this) {
			if(persistent()) container.activate(token, 1);
			boolean present = metadataPuttersByMetadata.containsKey(token);
			if(present) {
				oldState = metadataPuttersByMetadata.remove(token);
				if(persistent())
					container.activate(metadataPuttersUnfetchable, 2);
				if(metadataPuttersUnfetchable.containsKey(token)) {
					metadataPuttersUnfetchable.remove(token);

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

	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		Metadata m = (Metadata) oldState.getToken();
		if(persistent()) {
			container.activate(m, 100);
			container.activate(metadataPuttersUnfetchable, 2);
			container.activate(metadataPuttersByMetadata, 2);
		}
		synchronized(this) {
			if(metadataPuttersByMetadata.containsKey(m)) {
				ClientPutState prevState = metadataPuttersByMetadata.get(m);
				if(prevState != oldState) {
					if(logMINOR) Logger.minor(this, "Ignoring transition in "+this+" for metadata putter: "+oldState+" -> "+newState+" because current for "+m+" is "+prevState);
					container.deactivate(metadataPuttersUnfetchable, 1);
					container.deactivate(metadataPuttersByMetadata, 1);
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
			container.deactivate(m, 100);
			container.deactivate(metadataPuttersUnfetchable, 2);
			container.deactivate(metadataPuttersByMetadata, 2);
		}
	}

	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(ctx, 1);
			container.activate(ctx.eventProducer, 1);
		}
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.blockSetFinalized), container, context);
	}

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
		Iterator<String> i = bucketsByName.keySet().iterator();
		while(i.hasNext()) {
			String name = i.next();
			Object o = bucketsByName.get(name);
			if(o instanceof ManifestElement) {
				manifestEntries.put(name, o);
			} else if(o instanceof Bucket) {
				Bucket data = (Bucket) bucketsByName.get(name);
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
		Vector<ManifestElement> v = new Vector<ManifestElement>();
		flatten(manifestElements, v, "");
		return v.toArray(new ManifestElement[v.size()]);
	}

	public static void flatten(HashMap<String,Object> manifestElements, Vector<ManifestElement> v, String prefix) {
		Iterator<String> i = manifestElements.keySet().iterator();
		while(i.hasNext()) {
			String name = i.next();
			String fullName = prefix.length() == 0 ? name : prefix+ '/' +name;
			Object o = manifestElements.get(name);
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

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		Metadata m = (Metadata) state.getToken();
		if(persistent()) {
			container.activate(m, 100);
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

}

