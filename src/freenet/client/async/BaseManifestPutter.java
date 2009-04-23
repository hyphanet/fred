/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Stack;
import java.util.Vector;

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
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.NativeThread;

/**
 * This class contains all the insert logic, but not any 'pack logic'.
 * The pack logic have to be implement in a subclass in makePutHandlers.
 * @see PlainManifestPutter and @see DefaultManifestPutter.
 * 
 * Internal container redirect URIs:
 *  The internal container URIs should be always redirects to CHKs, not just include the metadata into manifest only.
 *  The (assumed) default behavior is the reuse of containers between editions,
 *  also ArchiveManger want to have a URI given, not Metadata.
 *  This rule also makes site update code/logic much more easier.
 * 
 * container mode: the metadata are inside the root container (the final URI points to an archive)
 * freeform mode: the metadata are inserted separately.(the final URI points to a SimpleManifest)
 */
public abstract class BaseManifestPutter extends BaseClientPutter implements PutCompletionCallback {

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
	
	// Only implements PutCompletionCallback for the final metadata insert
	protected class PutHandler extends BaseClientPutter implements PutCompletionCallback {
		
		protected PutHandler(final BaseManifestPutter smp, String name, Bucket data, ClientMetadata cm, boolean getCHKOnly) {
			this(smp, null, name, data, cm, getCHKOnly);
		}
			
		protected PutHandler(final BaseManifestPutter smp, PutHandler parent, String name, Bucket data, ClientMetadata cm, boolean getCHKOnly) {
			super(smp.priorityClass, smp.client);
			this.persistent = BaseManifestPutter.this.persistent();
			this.cm = cm;
			this.name = name;
			InsertBlock block = 
				new InsertBlock(data, cm, persistent() ? FreenetURI.EMPTY_CHK_URI.clone() : FreenetURI.EMPTY_CHK_URI);
			this.origSFI =
				new SingleFileInserter(this, this, block, false, ctx, false, getCHKOnly, true, null, null, false, null, earlyEncode);
			metadata = null;
			parentPutHandler = parent;
			isArchive = isContainer = false;
		}
		
		protected PutHandler(final BaseManifestPutter smp, PutHandler parent, String name, HashMap<String, Object> data, FreenetURI insertURI, ClientMetadata cm, boolean getCHKOnly, boolean isArchive2) {
			super(smp.priorityClass, smp.client);
			this.persistent = BaseManifestPutter.this.persistent();
			this.cm = cm;
			this.name = name;
			this.origSFI =
				new ContainerInserter(this, this, data, (persistent ? insertURI.clone() : insertURI), ctx, false, getCHKOnly, false, null, ARCHIVE_TYPE.TAR, false, earlyEncode);
			metadata = null;
			parentPutHandler = parent;
			isContainer = true;
			isArchive = isArchive2;
		}

		protected PutHandler(final BaseManifestPutter smp, String name, FreenetURI target, ClientMetadata cm) {
			this(smp, null, name, name, target, cm);
		}
				
		protected PutHandler(final BaseManifestPutter smp, PutHandler parent, String name, String targetInArchive2, FreenetURI target, ClientMetadata cm) {
			super(smp.getPriorityClass(), smp.client);
			this.persistent = BaseManifestPutter.this.persistent();
			this.cm = cm;
			this.name = name;
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, target, cm);
			metadata = m;
			if(logMINOR) Logger.minor(this, "Simple redirect metadata: "+m);
			origSFI = null;
			parentPutHandler = parent;
			isArchive = isContainer = false;
			targetInArchive = targetInArchive2;
		}
		
		private ClientPutState origSFI;
		private ClientPutState currentState;
		private ClientMetadata cm;
		private Metadata metadata;
		private String targetInArchive;
		private final String name;
		private final boolean persistent;
		private final PutHandler parentPutHandler;
		private final boolean isContainer;
		private final boolean isArchive;
		
		public void start(ObjectContainer container, ClientContext context) throws InsertException {
			if (logDEBUG)
				Logger.debug(this, "Starting a PutHandler for '"+this.name+"' (isRootContainer="+ (this==rootContainerPutHandler)+", isArchive="+this.isArchive+") " + this);
			
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
			if(logMINOR) Logger.minor(this, "Cancelling "+this, new Exception("debug"));
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
			if(logMINOR) Logger.minor(this, "Finished "+this, new Exception("debug"));
			return BaseManifestPutter.this.finished || cancelled || BaseManifestPutter.this.cancelled;
		}

		public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "Completed '"+this.name+"' "+this);
			
			
			if (isArchive) {
				
				Vector<PutHandler> phv = putHandlersArchiveTransformMap.get(this);
				for (PutHandler ph: phv) {
//					
					perContainerPutHandlersWaitingForFetchable.get(ph.parentPutHandler).remove(this);
//					Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, archiveURI.setDocName(targetInArchive), cm);
//					hm.put(ph.name, m);
//					putHandlersTransformMap.remove(ph);
					try {
						maybeStartParentContainer(ph.parentPutHandler, container, context);
					} catch (InsertException e) {
						fail(new InsertException(InsertException.INTERNAL_ERROR, e, null), container, context);
						return;
					}
				}
				return;
			}
			
			if(persistent) {
				container.activate(BaseManifestPutter.this, 1);
				container.activate(runningPutHandlers, 2);
			}
			BaseManifestPutter.this.onFetchable(this, container);
			ClientPutState oldState;
			boolean insertedAllFiles = true;
			synchronized(this) {
				oldState = currentState;
				currentState = null;
			}
			synchronized(BaseManifestPutter.this) {
				if(persistent) container.store(this);
				runningPutHandlers.remove(this);
				if(persistent) {
					container.ext().store(runningPutHandlers, 2);
					container.activate(putHandlersWaitingForMetadata, 2);
				}
				if(putHandlersWaitingForMetadata.contains(this)) {
					putHandlersWaitingForMetadata.remove(this);
					container.ext().store(putHandlersWaitingForMetadata, 2);
					Logger.error(this, "PutHandler '"+this.name+"' was in waitingForMetadata in onSuccess() on "+this+" for "+BaseManifestPutter.this);
				}
				
				if(persistent) {
					container.deactivate(putHandlersWaitingForMetadata, 1);
					container.activate(waitingForBlockSets, 2);
				}
				if(waitingForBlockSets.contains(this)) {
					waitingForBlockSets.remove(this);
					container.store(waitingForBlockSets);
					Logger.error(this, "PutHandler was in waitingForBlockSets in onSuccess() on "+this+" for "+BaseManifestPutter.this);
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
					if(logMINOR) Logger.minor(this, "PutHandler was in waitingForFetchable in onSuccess() on "+this+" for "+BaseManifestPutter.this);
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
			if (persistent) {
				if(oldState != null && oldState != state) {
					container.activate(oldState, 1);
					oldState.removeFrom(container, context);
				} else if(state != null) {
					state.removeFrom(container, context);
				}
			}
			if(insertedAllFiles)
				insertedAllFiles(container, context);
			if(persistent) {
				container.deactivate(runningPutHandlers, 1);
				container.deactivate(BaseManifestPutter.this, 1);
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
				container.activate(BaseManifestPutter.this, 1);
			fail(e, container, context);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}

		public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "onEncode("+key+") for "+this);
			System.out.println("Got a URI: "+key.getURI().toString(false, false) + " for "+this);
			if (isArchive) {
				
				FreenetURI archiveURI = key.getURI();
				
				Vector<PutHandler> phv = putHandlersArchiveTransformMap.get(this);
				for (PutHandler ph: phv) {
					HashMap<String, Object> hm = putHandlersTransformMap.get(ph);
					
					perContainerPutHandlersWaitingForFetchable.get(ph.parentPutHandler).remove(this);
					if (ph.targetInArchive == null) throw new NullPointerException();
					Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, archiveURI.setMetaString(new String[]{ph.targetInArchive}), cm);
					hm.put(ph.name, m);
					putHandlersTransformMap.remove(ph);
				}
				//perContainerPutHandlersWaitingForFetchable.get(parentContainerHandle).remove(this);
				//putHandlersArchiveTransformMap.remove(this);
				return;
			}

			if(metadata == null) {
				// The file was too small to have its own metadata, we get this instead.
				// So we make the key into metadata.
				if(persistent) {
					container.activate(key, 5);
					container.activate(BaseManifestPutter.this, 1);
				}
				Metadata m =
					new Metadata(Metadata.SIMPLE_REDIRECT, null, null, key.getURI(), cm);
				onMetadata(m, null, container, context);
				if(persistent) {
					container.deactivate(BaseManifestPutter.this, 1);
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
			if(logMINOR) Logger.minor(this, "Assigning metadata: "+m+" for '"+this.name+"' "+this+" from "+state+" persistent="+persistent);
			if(metadata != null) {
				Logger.error(this, "Reassigning metadata", new Exception("debug"));
				return;
			}
			metadata = m;
			
			if (isContainer) {
				// containers are inserted with reportMetadataOnly=false,
				// so it can never reach here
				throw new IllegalStateException();
			}
			
			if(persistent) {
				container.activate(BaseManifestPutter.this, 1);
				container.activate(putHandlersWaitingForMetadata, 2);
			}
			boolean allMetadatas = false;
			
			synchronized(BaseManifestPutter.this) {
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
				container.deactivate(BaseManifestPutter.this, 1);
			}
		}

		@Override
		public void addBlock(ObjectContainer container) {
			if(persistent) {
				container.activate(BaseManifestPutter.this, 1);
			}
			BaseManifestPutter.this.addBlock(container);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}
		
		@Override
		public void addBlocks(int num, ObjectContainer container) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.addBlocks(num, container);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}
		
		@Override
		public void completedBlock(boolean dontNotify, ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.completedBlock(dontNotify, container, context);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}
		
		@Override
		public void failedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.failedBlock(container, context);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}
		
		@Override
		public void fatallyFailedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.fatallyFailedBlock(container, context);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}
		
		@Override
		public void addMustSucceedBlocks(int blocks, ObjectContainer container) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.addMustSucceedBlocks(blocks, container);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}
		
		@Override
		public void notifyClients(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.notifyClients(container, context);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}

		public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(BaseManifestPutter.this, 1);
				container.activate(waitingForBlockSets, 2);
			}
			boolean allBlockSets = false;
			synchronized(BaseManifestPutter.this) {
				waitingForBlockSets.remove(this);
				if(persistent)
					container.store(waitingForBlockSets);
				allBlockSets = waitingForBlockSets.isEmpty();
			}
			if(allBlockSets)
				BaseManifestPutter.this.blockSetFinalized(container, context);
			if(persistent) {
				container.deactivate(waitingForBlockSets, 1);
				container.deactivate(BaseManifestPutter.this, 1);
			}
		}

		@Override
		public void onMajorProgress(ObjectContainer container) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.onMajorProgress(container);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}

		public void onFetchable(ClientPutState state, ObjectContainer container) {
			if(logMINOR) Logger.minor(this, "onFetchable "+this, new Exception("debug"));
			
			if (isArchive) {
				
				
				
				//perContainerPutHandlersWaitingForFetchable.get(containerHandle).remove(this);
				
				return;

			}
			
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.onFetchable(this, container);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
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
				Logger.error(this, "origSFI is set in removeFrom() on "+this+" for "+BaseManifestPutter.this, new Exception("debug"));
				container.activate(oldSFI, 1);
				oldSFI.cancel(container, context);
				oldSFI.removeFrom(container, context);
				if(oldState == oldSFI) oldState = null;
			}
			if(oldState != null) {
				Logger.error(this, "currentState is set in removeFrom() on "+this+" for "+BaseManifestPutter.this, new Exception("debug"));
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
				Logger.normal(this, "Metadata is set in removeFrom() on "+this+" for "+BaseManifestPutter.this);
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
		public String toString() {
			if (logDEBUG)
				return super.toString() + "{Name="+this.name+", isRootContainer="+ (this==rootContainerPutHandler)+", isContainer="+this.isContainer+", isArchive="+this.isArchive+'}';
			return super.toString();
		}

		@Override
		protected void innerToNetwork(ObjectContainer container, ClientContext context) {
			// Ignore
		}

	}

	// if true top level metadata is a container
	private boolean containerMode = false;
	private ContainerBuilder rootContainer;
	private PutHandler rootContainerPutHandler;
	private HashSet<PutHandler> containerPutHandlers;
	private HashMap<PutHandler, HashSet<PutHandler>> perContainerPutHandlersWaitingForMetadata;
	private HashMap<PutHandler, HashSet<PutHandler>> perContainerPutHandlersWaitingForFetchable;
	private HashMap<PutHandler, HashMap<String, Object>> putHandlersTransformMap;
	private HashMap<PutHandler, Vector<PutHandler>> putHandlersArchiveTransformMap;
	
	private HashMap<String,Object> putHandlersByName;
	private HashSet<PutHandler> runningPutHandlers;
	private HashSet<PutHandler> putHandlersWaitingForMetadata;
	private HashSet<PutHandler> waitingForBlockSets;
	private HashSet<PutHandler> putHandlersWaitingForFetchable;
	private FreenetURI finalURI;
	private final FreenetURI targetURI;
	private boolean finished;
	private final InsertContext ctx;
	final ClientCallback cb;
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
	private boolean fetchable;
	private final boolean earlyEncode;
	
	public BaseManifestPutter(ClientCallback cb, 
			HashMap<String, Object> manifestElements, short prioClass, FreenetURI target, 
			String defaultName, InsertContext ctx, boolean getCHKOnly2, RequestClient clientContext, boolean earlyEncode) {
		super(prioClass, clientContext);
		this.defaultName = defaultName;
		if(client.persistent())
			this.targetURI = target.clone();
		else
			this.targetURI = target;
		this.cb = cb;
		this.ctx = ctx;
		this.getCHKOnly = getCHKOnly2;
		this.earlyEncode = earlyEncode;
		putHandlersByName = new HashMap<String,Object>();
		runningPutHandlers = new HashSet<PutHandler>();
		putHandlersWaitingForMetadata = new HashSet<PutHandler>();
		putHandlersWaitingForFetchable = new HashSet<PutHandler>();
		waitingForBlockSets = new HashSet<PutHandler>();
		metadataPuttersByMetadata = new HashMap<Metadata,ClientPutState>();
		metadataPuttersUnfetchable = new HashMap<Metadata,ClientPutState>();
		
		containerPutHandlers = new HashSet<PutHandler>();
		perContainerPutHandlersWaitingForMetadata = new HashMap<PutHandler, HashSet<PutHandler>>();
		perContainerPutHandlersWaitingForFetchable = new HashMap<PutHandler, HashSet<PutHandler>>();
		putHandlersTransformMap = new HashMap<PutHandler, HashMap<String, Object>>();
		putHandlersArchiveTransformMap = new HashMap<PutHandler, Vector<PutHandler>>();
		makePutHandlers(manifestElements, putHandlersByName);
	}

	public void start(ObjectContainer container, ClientContext context) throws InsertException {
		if (logMINOR)
			Logger.minor(this, "Starting " + this+" persistence="+persistent()+ " containermode="+containerMode);
		PutHandler[] running;
		PutHandler[] containers;

		if(persistent()) {
			container.activate(runningPutHandlers, 2);
		}
		synchronized (this) {
			running = runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
			if (containerMode) {
				containers = getContainersToStart();
			} else {
				containers = null;
			}
		}
		
		try {
			boolean persistent = persistent(); // this might get deactivated ...
			for (int i = 0; i < running.length; i++) {
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
			
			if (containerMode) {
				for (int i = 0; i < containers.length; i++) {
					containers[i].start(container, context);
					if(persistent && !container.ext().isActive(this))
						container.activate(this, 1);
					if (logMINOR)
						Logger.minor(this, "Started " + i + " of " + containers.length);
					if (isFinished()) {
						if (logMINOR)
							Logger.minor(this, "Already finished, killing start() on " + this);
						return;
					}
				}
				if (logMINOR)
					Logger.minor(this, "Started " + containers.length + " PutHandler's (containers) for " + this);
				
			}
			if (!containerMode && running.length == 0) {
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
	
	private PutHandler[] getContainersToStart() {
		PutHandler[] maybeStartPH = containerPutHandlers.toArray(new PutHandler[containerPutHandlers.size()]);
		ArrayList<PutHandler> phToStart = new ArrayList<PutHandler>();
		
		for (PutHandler ph: maybeStartPH) {
			if (perContainerPutHandlersWaitingForMetadata.get(ph).isEmpty() && perContainerPutHandlersWaitingForFetchable.get(ph).isEmpty() ) {
				phToStart.add(ph); 
			}
		}
		if (maybeStartPH.length == 0) {
			phToStart.add(rootContainer.selfHandle);
		}
		return phToStart.toArray(new PutHandler[phToStart.size()]);
	}

	/**
	 * Implement the pack logic
	 * 
	 * @param manifestElements
	 * @param putHandlersByName
	 */
	protected abstract void makePutHandlers(HashMap<String, Object> manifestElements, HashMap<String,Object> putHandlersByName);

	@Override
	public FreenetURI getURI() {
		return finalURI;
	}

	@Override
	public synchronized boolean isFinished() {
		return finished || cancelled;
	}

	private final DBJob runGotAllMetadata = new DBJob() {

		public void run(ObjectContainer container, ClientContext context) {
			context.jobRunner.removeRestartJob(this, NativeThread.NORM_PRIORITY, container);
			container.activate(BaseManifestPutter.this, 1);
			innerGotAllMetadata(container, context);
			container.deactivate(BaseManifestPutter.this, 1);
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
			context.jobRunner.queueRestartJob(runGotAllMetadata, NativeThread.NORM_PRIORITY, container, false);
			context.jobRunner.queue(runGotAllMetadata, NativeThread.NORM_PRIORITY, false);
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
		if(logMINOR) Logger.minor(this, "Got all metadata");
		//if (!containerMode) {
		HashMap<String, Object> namesToByteArrays = new HashMap<String, Object>();
		// We'll end up committing it all anyway, and hash maps in hash maps can cause
		// *severe* problems (see COR-1582), so activate to max depth first.
		if(persistent()) container.activate(putHandlersByName, Integer.MAX_VALUE);
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
			//container.activate(elementsToPutInArchive, 2);
		}
		InsertBlock block;
		boolean isMetadata = true;
		ARCHIVE_TYPE archiveType = null;
		block = new InsertBlock(bucket, null, persistent() ? targetURI.clone() : targetURI);
		SingleFileInserter metadataInserter;
		try {
			metadataInserter = 
				new SingleFileInserter(this, this, block, isMetadata, ctx, (archiveType == ARCHIVE_TYPE.ZIP) , getCHKOnly, false, baseMetadata, archiveType, true, null, earlyEncode);
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
		}
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
					new SingleFileInserter(this, this, ib, true, ctx, false, getCHKOnly, false, m, null, true, null, earlyEncode);
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
		for(Map.Entry<String, Object> me : putHandlersByName.entrySet()) {
			String name = me.getKey();
			Object o = me.getValue();
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
					Logger.minor(this, "Putting "+name);
					namesToByteArrays.put(name, meta);
					if(logMINOR)
						Logger.minor(this, "Putting PutHandler into base metadata: "+ph+" name "+name);
				}
			} else if(o instanceof HashMap) {
				HashMap<String,Object> subMap = new HashMap<String,Object>();
				// Already activated
				namesToByteArrays.put(name, subMap);
				if(logMINOR) {
					Logger.minor(this, "Putting hashmap into base metadata: "+name+" size "+((HashMap)o).size()+" active = "+((container == null) ? "null" : Boolean.toString(container.ext().isActive(o))));
					Logger.minor(this, "Putting directory: "+name);
				}
				namesToByteArrays((HashMap<String, Object>)o, subMap, container);
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

		container.delete(runningPutHandlers);
		container.delete(putHandlersWaitingForMetadata);
		container.delete(waitingForBlockSets);
		container.delete(putHandlersWaitingForFetchable);
		runningPutHandlers = null;
		putHandlersWaitingForMetadata = null;
		waitingForBlockSets = null;
		putHandlersWaitingForFetchable = null;
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
				//if(elementsToPutInArchive.remove(handler))
				//	container.ext().store(elementsToPutInArchive, 2);
				handler.removeFrom(container, context);
			} else {
				@SuppressWarnings("unchecked")
				HashMap<String, Object> subMap = (HashMap<String, Object>) value;
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
						container.ext().store(metadataPuttersUnfetchable);
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
	protected void innerToNetwork(ObjectContainer container, ClientContext context) {
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
	

	private void maybeStartParentContainer(PutHandler containerHandle2, ObjectContainer container, ClientContext context) throws InsertException {
		if (perContainerPutHandlersWaitingForMetadata.get(containerHandle2).isEmpty() && perContainerPutHandlersWaitingForFetchable.get(containerHandle2).isEmpty()) {
			containerHandle2.start(container, context);
		}
	}


	// compose helper stuff

	protected final ClientMetadata guessMime(String name, ManifestElement me) {
		return guessMime(name, me.mimeOverride);
	}
		
	protected final ClientMetadata guessMime(String name, String mimetype) {	
		String mimeType = mimetype;
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
		if (containerMode) throw new IllegalStateException("You can not add freeform elements in container mode!");
		PutHandler ph;
		Bucket data = me.data;
		if(me.targetURI != null) {
			ph = new PutHandler(this, name, me.targetURI, cm);
			// Just a placeholder, don't actually run it
		} else {
			ph = new PutHandler(this, name, data, cm, getCHKOnly);
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
	
	public ContainerBuilder makeArchive() {
		return new ContainerBuilder(false, null, null, true);
	}

	protected ContainerBuilder getRootContainer() {
		if (!containerMode) {
			containerMode = true;
			rootContainer = new ContainerBuilder(true);
			putHandlersByName = null;
		}
		return rootContainer;
	}
	
	protected final class ContainerBuilder {
		
		private final HashMap<String, Object> rootDir;
		private HashMap<String, Object> currentDir;
		private final PutHandler selfHandle;
		
		private final Stack<HashMap<String, Object>> dirStack;
		
		private ContainerBuilder(boolean isRoot) {
			this(isRoot, null, null, false);
		}
		
		private ContainerBuilder(PutHandler parent, String name) {
			this(false, parent, name, false);
		}
		
		private ContainerBuilder(boolean isRoot, PutHandler parent, String name, boolean isArchive) {
			if (putHandlersByName != null && putHandlersByName.size() > 0) {
				throw new IllegalStateException("You can not add containers in free form mode!");
			}
			dirStack = new Stack<HashMap<String, Object>>();
			rootDir = new HashMap<String, Object>();
			selfHandle = new PutHandler(BaseManifestPutter.this, parent, name, rootDir, (isRoot?BaseManifestPutter.this.targetURI:FreenetURI.EMPTY_CHK_URI), null, getCHKOnly, isArchive);
			currentDir = rootDir;
			if (isRoot) {
				rootContainerPutHandler = selfHandle;
			} else {
				containerPutHandlers.add(selfHandle);
			}
			perContainerPutHandlersWaitingForMetadata.put(selfHandle, new HashSet<PutHandler>());
			perContainerPutHandlersWaitingForFetchable.put(selfHandle, new HashSet<PutHandler>());
			if (isArchive) putHandlersArchiveTransformMap.put(selfHandle, new Vector<PutHandler>());
		}
		
		public ContainerBuilder makeSubContainer(String name) {
			ContainerBuilder subCon = new ContainerBuilder(selfHandle, name);
			currentDir.put(name , subCon.selfHandle);
			putHandlersTransformMap.put(subCon.selfHandle, currentDir);
			perContainerPutHandlersWaitingForFetchable.get(selfHandle).add(subCon.selfHandle);
			return subCon;
		}
		
		public void makeSubDirCD(String name) {
			currentDir = makeSubDir(currentDir, name);
		}
		
		private HashMap<String, Object> makeSubDir(HashMap<String, Object> parentDir, String name) {
			HashMap<String, Object> newDir = new HashMap<String, Object>();
			parentDir.put(name , newDir);
			return newDir;
		}
		
		public void addItem(String name, String nameInArchive, String mimeOverride, Bucket data) {
			addItem(name, nameInArchive, mimeOverride, data, false);
		}
		
		public void addItem(String name, String nameInArchive, String mimeOverride, Bucket data, boolean isDefaultDoc) {
			ManifestElement element = new ManifestElement(name, nameInArchive, data, mimeOverride, data.size());
			addElement(name, element, isDefaultDoc);
		}
		
		public void addRedirect(String name, String mimeOverride, FreenetURI targetURI2) {
			addRedirect(name, mimeOverride, targetURI2, false);
		}
		
		public void addRedirect(String name, String mimeOverride, FreenetURI targetURI2, boolean isDefaultDoc) {
			ManifestElement element = new ManifestElement(name, targetURI2, mimeOverride);
			addElement(name, element, isDefaultDoc);
		}
		
		public void addExternal(String name, String mimeOverride, Bucket data) {
			addExternal(name, mimeOverride, data, false);
		}
		
		public void addExternal(String name, String mimeOverride, Bucket data, boolean isDefaultDoc) {
			PutHandler ph = new PutHandler(BaseManifestPutter.this, selfHandle, name, data, guessMime(name, mimeOverride), getCHKOnly);
			runningPutHandlers.add(ph);
			perContainerPutHandlersWaitingForMetadata.get(selfHandle).add(ph);
			putHandlersTransformMap.put(ph, currentDir);
		}
		
		private void addElement(String name, ManifestElement element, boolean isDefaultDoc) {
			currentDir.put(name, element);
			if (isDefaultDoc) currentDir.put("", element);
		}

		public void addArchiveItem(ContainerBuilder archive, String name, String nameInArchive, String mimeTypeOverride, Bucket data) {
			addArchiveItem(archive, name, nameInArchive, mimeTypeOverride, data, false);
		}
		
		public void addArchiveItem(ContainerBuilder archive, String name, String nameInArchive, String mimeTypeOverride, Bucket data, boolean isDefaultDoc) {
			archive.addItem(name, nameInArchive, mimeTypeOverride, data, isDefaultDoc);
			PutHandler ph = new PutHandler(BaseManifestPutter.this, selfHandle, name, nameInArchive, FreenetURI.EMPTY_CHK_URI, guessMime(name, mimeTypeOverride));
			putHandlersTransformMap.put(ph, currentDir);
			perContainerPutHandlersWaitingForFetchable.get(selfHandle).add(archive.selfHandle);
			putHandlersArchiveTransformMap.get(archive.selfHandle).add(ph);
		}

		public void pushCurrentDir() {
			dirStack.push(currentDir);
		}

		public void popCurrentDir() {
			currentDir = dirStack.pop();
		}
	}
	
}

