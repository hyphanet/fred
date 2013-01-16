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
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.Metadata.SimpleManifestComposer;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.Key;
import freenet.node.RequestClient;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.io.NativeThread;

/**
 * <P>This class contains all the insert logic, but not any 'pack logic'.
 * The pack logic have to be implement in a subclass in makePutHandlers.
 * <P>
 * Internal container redirect URIs:
 *  The internal container URIs should be always redirects to CHKs, not just include the metadata into manifest only.
 *  The (assumed) default behavior is the reuse of containers between editions,
 *  also ArchiveManger want to have a URI given, not Metadata.
 *  This rule also makes site update code/logic much more easier.
 * <P>
 * <DL>
 * <DT>container mode: <DD>the metadata are inside the root container (the final URI points to an archive)
 * <DT>freeform mode: <DD>the metadata are inserted separately.(the final URI points to a SimpleManifest)
 * </DL>
 * @see {@link PlainManifestPutter} and {@link DefaultManifestPutter}</P>
 */
public abstract class BaseManifestPutter extends ManifestPutter {

	// FIXME: DB4O ISSUE: HASHMAP ACTIVATION:
	// REDFLAG MUST BE FIXED BEFORE DEPLOYING THE NEW PUTTERS !!!
	
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
	
	// Options for a real fix:
	// - Assign each PutHandler a Long id, replace Set<X> with Map<Long,X>, and activate to depth 2.
	// - Use IdentityHashMap instead of HashMap.
	// - Implement a custom class similar to IdentityHashMap which doesn't activate a bucket unless it needs to and uses db4o ID's.
	
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerClass(BaseManifestPutter.class);
	}
	
	/**
	 * ArchivePutHandler - wrapper for ContainerInserter
	 *
	 * Archives are not part of the site structure, they are used to group files that
	 * not fit into a container (for example a directory with brazilion files it)
	 * Archives are always inserted as CHK, references to items in it
	 * are normal redirects to CHK@blah,blub,AA/nameinarchive
	 *
	 */
	private final class ArchivePutHandler extends PutHandler {

		private ArchivePutHandler(BaseManifestPutter bmp, PutHandler parent, String name, HashMap<String, Object> data, FreenetURI insertURI, boolean getCHKOnly) {
			super(bmp, parent, name, null, containerPutHandlers, null);
			this.origSFI = new ContainerInserter(this, this, data, (persistent ? insertURI.clone() : insertURI), ctx, false, getCHKOnly, false, null, ARCHIVE_TYPE.TAR, false, earlyEncode, forceCryptoKey, cryptoAlgorithm, realTimeFlag);
		}

		@Override
		public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
			if (logMINOR) Logger.minor(this, "onEncode(" + key.getURI().toString(false, false) + ") for " + this);

			if(persistent) {
				container.activate(key, 5);
				container.activate(BaseManifestPutter.this, 2);
			}
			synchronized (BaseManifestPutter.this) {
				// transform the placeholders to redirects (redirects to 'uri/name') and
				// remove from waitfor lists
				Vector<PutHandler> phv = putHandlersArchiveTransformMap.get(this);
				for (PutHandler ph : phv) {
					HashMap<String, Object> hm = putHandlersTransformMap.get(ph);
					perContainerPutHandlersWaitingForMetadata.get(ph.parentPutHandler).remove(ph);
					if(persistent) container.ext().store(perContainerPutHandlersWaitingForMetadata, 2);
					if (ph.targetInArchive == null)
						throw new NullPointerException();
					Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, key.getURI().setMetaString(new String[] { ph.targetInArchive }), cm);
					hm.put(ph.itemName, m);
					if(persistent) container.ext().store(hm, 2);
					putHandlersTransformMap.remove(ph);
					if(persistent) container.ext().store(putHandlersTransformMap, 2);
					try {
						tryStartParentContainer(ph.parentPutHandler, container, context);
					} catch (InsertException e) {
						fail(new InsertException(InsertException.INTERNAL_ERROR, e, null), 	container, context);
						return;
					}
				}
				putHandlersArchiveTransformMap.remove(this);
				if(persistent) container.ext().store(putHandlersArchiveTransformMap, 2);
			}
			if(persistent) {
				container.deactivate(BaseManifestPutter.this, 1);
			}

		}

		@Override
		public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
			if (logMINOR) Logger.minor(this, "Completed '" + this.itemName + "' " + this);
			if(persistent) {
				container.activate(BaseManifestPutter.this, 1);
			}
			if (!containerPutHandlers.remove(this)) throw new IllegalStateException("was not in containerPutHandlers");
			;
			super.onSuccess(state, container, context);
			if(persistent) {
				container.deactivate(BaseManifestPutter.this, 1);
			}
		}
	}

	/**
	 * ContainerPutHandler - wrapper for ContainerInserter
	 *
	 * Containers are an integral part of the site structure, they are
	 * inserted as CHK, the root container is inserted at targetURI.
	 * references to items in it are ARCHIVE_INTERNAL_REDIRECT
	 *
	 */
	private final class ContainerPutHandler extends PutHandler {

		private ContainerPutHandler(BaseManifestPutter bmp, PutHandler parent, String name, HashMap<String, Object> data, FreenetURI insertURI, Object object, boolean getCHKOnly, HashSet<PutHandler> runningMap) {
			super(bmp, parent, name, null, runningMap, null);
			this.origSFI = new ContainerInserter(this, this, data, (persistent ? insertURI.clone() : insertURI), ctx, false, getCHKOnly, false, null, ARCHIVE_TYPE.TAR, false, earlyEncode, forceCryptoKey, cryptoAlgorithm, realTimeFlag);
		}

		@Override
		public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
			if (logMINOR) Logger.minor(this, "onEncode(" + key.getURI().toString(false, false) + ") for " + this);

			if(persistent) {
				container.activate(key, 5);
				container.activate(BaseManifestPutter.this, 1);
			}

			if (rootContainerPutHandler == this) {
				finalURI = key.getURI();
				if(persistent())
					container.activate(cb, 1);
				cb.onGeneratedURI(persistent() ? finalURI.clone() : finalURI, this, container);
				if(persistent()) {
					container.deactivate(cb, 1);
					container.store(this);
				}
			} else {
				synchronized (BaseManifestPutter.this) {
					HashMap<String, Object> hm = putHandlersTransformMap.get(this);
					perContainerPutHandlersWaitingForMetadata.get(parentPutHandler).remove(this);
					Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, key.getURI(), cm);
					hm.put(this.itemName, m);
					if (persistent)
						container.ext().store(hm, 2);
					putHandlersTransformMap.remove(this);
					if (persistent)
						container.ext().store(putHandlersTransformMap, 2);

					try {
						tryStartParentContainer(parentPutHandler, container, context);
					} catch (InsertException e) {
						fail(e, container, context);
						return;
					}
				}
			}

			if(persistent) {
				//System.out.println("BMP deactivated encode");
				container.deactivate(BaseManifestPutter.this, 1);
			}
		}

		@Override
		public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
			if (logMINOR) Logger.minor(this, "Completed '" + this.itemName + "' " + this);

			if(persistent) {
				container.activate(BaseManifestPutter.this, 1);
			}

			if (rootContainerPutHandler == this) {
				if (containerPutHandlers.contains(this)) throw new IllegalStateException("was in containerPutHandlers");
				rootContainerPutHandler = null;
			} else {
				if (!containerPutHandlers.remove(this)) throw new IllegalStateException("was not in containerPutHandlers");
			}
			super.onSuccess(state, container, context);

			if(persistent) {
				//System.out.println("BMP deactivated success");
				container.deactivate(BaseManifestPutter.this, 1);
			}
		}
	}

	private final class ExternPutHandler extends PutHandler {

		private ExternPutHandler(BaseManifestPutter bmp, PutHandler parent, String name, Bucket data, ClientMetadata cm2, boolean getCHKOnly2) {
			super(bmp, parent, name, cm2, runningPutHandlers, null);
			InsertBlock block = new InsertBlock(data, cm, persistent() ? FreenetURI.EMPTY_CHK_URI.clone() : FreenetURI.EMPTY_CHK_URI);
			this.origSFI = new SingleFileInserter(this, this, block, false, ctx, realTimeFlag, false, getCHKOnly2, true, null, null, false, null, earlyEncode, false, persistent(), 0, 0, null, cryptoAlgorithm, forceCryptoKey, -1);
		}

		@Override
		public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
			if (logMINOR) Logger.minor(this, "onEncode(" + key + ") for " + this);

			//debugDecompose("ExternPutHandler.onEncode Begin");
			if(metadata != null) {
				Logger.error(this, "Reassigning metadata: "+metadata, new Exception("debug"));
				//throw new IllegalStateException("Metadata set but we got a uri?!");
			}
			// The file was too small to have its own metadata, we get this instead.
			// So we make the key into metadata.
			if(persistent) {
				container.activate(key, 5);
				container.activate(BaseManifestPutter.this, 1);
			}
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, key.getURI(), cm);
			onMetadata(m, state, container, context);
			if(persistent) {
				container.deactivate(BaseManifestPutter.this, 1);
			}
			//debugDecompose("ExternPutHandler.onEncode End");
		}

		@Override
		public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
			//new Error("DEBUGME").printStackTrace();
			//debugDecompose("ExternPutHandler.onMetadata Begin");
			if(logMINOR) Logger.minor(this, "Assigning metadata: "+m+" for '"+this.itemName+"' "+this+" from "+state+" persistent="+persistent);
			if(metadata != null) {
				Logger.error(this, "Reassigning metadata", new Exception("debug"));
				return;
			}
			metadata = m;

			if(persistent) {
				container.activate(BaseManifestPutter.this, 1);
			}

			if (freeformMode) {
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
			} else if (containerMode) {
				HashMap<String, Object> hm = putHandlersTransformMap.get(this);
				perContainerPutHandlersWaitingForMetadata.get(parentPutHandler).remove(this);
				hm.put(this.itemName, m);
				if(persistent) {
					container.ext().store(hm, 2);
				}
				putHandlersTransformMap.remove(this);
				if(persistent) {
					container.ext().store(perContainerPutHandlersWaitingForMetadata, 2);
					container.ext().store(putHandlersTransformMap, 2);
				}
				try {
					tryStartParentContainer(parentPutHandler, container, context);
				} catch (InsertException e) {
					fail(e, container, context);
					return;
				}
			} else {
				throw new RuntimeException("Neiter container nor freeform mode. Hu?");
			}
			//debugDecompose("ExternPutHandler.onMetadata End");
			if(persistent) {
				container.deactivate(BaseManifestPutter.this, 1);
			}
		}

		@Override
		public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
			super.onSuccess(state, container, context);
		}
	}

	// meta data inserter / resolver
	// these MPH are usually created on demand, so they are outside (main)constructor →needs db4o update
	private final class MetaPutHandler extends PutHandler {

		// Metadata is not put with a cryptokey. It is derived from other stuff that is already encrypted with random keys.
		
		// final metadata
		private MetaPutHandler(BaseManifestPutter smp, PutHandler parent, InsertBlock insertBlock, boolean getCHKOnly, ObjectContainer container) {
			super(smp, parent, null, null, null, container);
			// Treat as splitfile for purposes of determining number of reinserts.
			this.origSFI = new SingleFileInserter(this, this, insertBlock, true, ctx, realTimeFlag, false, getCHKOnly, false, null, null, true, null, earlyEncode, true, persistent(), 0, 0, null, cryptoAlgorithm, null, -1);
			if(logMINOR) Logger.minor(this, "Inserting root metadata: "+origSFI);
		}

		// resolver
		private MetaPutHandler(BaseManifestPutter smp, PutHandler parent, Metadata toResolve, boolean getCHKOnly, BucketFactory bf, ObjectContainer container) throws MetadataUnresolvedException, IOException {
			super(smp, parent, null, null, runningPutHandlers, container);
			Bucket b = toResolve.toBucket(bf);
			metadata = toResolve;
			// Treat as splitfile for purposes of determining number of reinserts.
			InsertBlock ib = new InsertBlock(b, null, persistent() ? FreenetURI.EMPTY_CHK_URI.clone() : FreenetURI.EMPTY_CHK_URI);
			this.origSFI = new SingleFileInserter(this, this, ib, true, ctx, realTimeFlag, false, getCHKOnly, false, toResolve, null, true, null, earlyEncode, true, persistent(), 0, 0, null, cryptoAlgorithm, null, -1);
			if(logMINOR) Logger.minor(this, "Inserting subsidiary metadata: "+origSFI+" for "+toResolve);
		}

		@Override
		public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
			if (logMINOR) Logger.minor(this, "onEncode(" + key.getURI().toString(false, false) + ") for " + this);

			if (rootMetaPutHandler == this) {
				finalURI = key.getURI();
				if(persistent())
					container.activate(cb, 1);
				cb.onGeneratedURI(persistent() ? finalURI.clone() : finalURI, this, container);
				if(persistent()) {
					container.deactivate(cb, 1);
					container.store(this);
				}
				return;
			}

			metadata.resolve(key.getURI());
		}

		@Override
		public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
			boolean wasRoot = false;
			synchronized (BaseManifestPutter.this) {
				if (rootMetaPutHandler == this) {
					//if (containerPutHandlers.contains(this)) throw new IllegalStateException("was in containerPutHandlers");
					rootMetaPutHandler = null;
					wasRoot = true;
				}
			}
			if (!wasRoot)
				resolveAndStartBase(container, context);
			super.onSuccess(state, container, context);

		}
	}

	/** Placeholder for Matadata, don't run it! */
	private final class JokerPutHandler extends PutHandler {

		/** a normal ( freeform) redirect */
		public JokerPutHandler(BaseManifestPutter bmp, 	String name, FreenetURI targetURI2, ClientMetadata cm2) {
			super(bmp, null, name, null, (Metadata)null, cm2);
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, targetURI2, cm2);
			metadata = m;
		}

		/** an archive redirect */
		public JokerPutHandler(BaseManifestPutter bmp, PutHandler parent, String name, ClientMetadata cm2) {
			super(bmp, parent, name, name, (Metadata)null, cm2);
			// we dont know the final uri, so preconstructing the metadata does not help here			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, FreenetURI.EMPTY_CHK_URI, cm2);
		}

		/** a short symlink */
		public JokerPutHandler(BaseManifestPutter bmp, PutHandler parent, String name, String target) {
			super(bmp, parent, name, name, (Metadata)null, null);
			Metadata m = new Metadata(Metadata.SYMBOLIC_SHORTLINK, null, null, target, null);
			metadata = m;
		}

	}

	// Only implements PutCompletionCallback for the final metadata insert
	private abstract class PutHandler extends BaseClientPutter implements PutCompletionCallback {

		// run me
		private PutHandler(final BaseManifestPutter bmp, PutHandler parent, String name, ClientMetadata cm, HashSet<PutHandler> runningMap, ObjectContainer container) {
			super(bmp.priorityClass, bmp.client);
			this.persistent = bmp.persistent();
			this.cm = cm;
			this.itemName = name;
			metadata = null;
			parentPutHandler = parent;

			if (runningMap != null) {
				synchronized (runningMap) {
					if (runningMap.contains(this)) {
						Logger.error(this, "PutHandler already in 'runningMap': "+runningMap, new Error("error"));
					} else {
						runningMap.add(this);
						if (container != null) {
							container.ext().store(runningMap, 2);
						}
					}
				}
			}

			synchronized (putHandlerWaitingForBlockSets) {
				if (putHandlerWaitingForBlockSets.contains(this)) {
					Logger.error(this, "PutHandler already in 'waitingForBlockSets'!", new Error("error"));
				} else {
					putHandlerWaitingForBlockSets.add(this);
					if (container != null) {
						container.ext().store(putHandlerWaitingForBlockSets, 2);
					}
				}
			}

			synchronized (putHandlersWaitingForFetchable) {
				if (putHandlersWaitingForFetchable.contains(this)) {
					Logger.error(this, "PutHandler already in 'waitingForFetchable'!", new Error("error"));
				} else {
					putHandlersWaitingForFetchable.add(this);
					if (container != null) {
						container.ext().store(putHandlersWaitingForFetchable, 2);
					}
				}
			}
		}

		// place holder, don't run it
		private PutHandler(final BaseManifestPutter bmp, PutHandler parent, String name, String nameInArchive, Metadata md, ClientMetadata cm) {
			super(bmp.priorityClass, bmp.client);
			this.persistent = bmp.persistent();
			this.cm = cm;
			this.itemName = name;
			this.origSFI = null;
			metadata = md;
			parentPutHandler = parent;
			this.targetInArchive = nameInArchive;
		}

		protected ClientPutState origSFI;
		private ClientPutState currentState;
		protected ClientMetadata cm;
		protected Metadata metadata;
		private String targetInArchive;
		protected final String itemName;
		protected final boolean persistent;
		protected final PutHandler parentPutHandler;

		public void start(ObjectContainer container, ClientContext context) throws InsertException {
			//new Error("trace start "+this).printStackTrace();
			if (logDEBUG)
				Logger.debug(this, "Starting a PutHandler for '"+this.itemName+"' "+ this);

			if (origSFI == null) {
				fail(new IllegalStateException("origSFI is null on start(), impossible"), container, context);
			}

			if ((!(this instanceof MetaPutHandler)) && (metadata != null)) {
				fail(new IllegalStateException("metdata=" + metadata + " on start(), impossible"), container, context);
			}

			boolean ok;
			if ((this instanceof ContainerPutHandler) || (this instanceof ArchivePutHandler)) {
				if (this != rootContainerPutHandler) {
					synchronized (containerPutHandlers) {
						ok = containerPutHandlers.contains(this);
					}
					if (!ok) {
						throw new IllegalStateException("Starting a PutHandler thats not in 'containerPutHandlers'! "+this);
					}
				}
			} else {
				if (this != rootMetaPutHandler) {
					synchronized (runningPutHandlers) {
						ok = runningPutHandlers.contains(this);
					}
					if (!ok) {
						throw new IllegalStateException("Starting a PutHandler thats not in 'runningPutHandlers'! "+this);
					}
				}
			}

			if (persistent && !container.ext().isActive(putHandlerWaitingForBlockSets)) {
				new Error("why deactivated? putHandlerWaitingForBlockSets "+this+" ["+BaseManifestPutter.this+"]").printStackTrace();
				container.activate(putHandlerWaitingForBlockSets, 2);
			}

			synchronized (putHandlerWaitingForBlockSets) {
				ok = putHandlerWaitingForBlockSets.contains(this);
			}
			if (!ok) {
				Logger.error(this, "Starting a PutHandler thats not in 'waitingForBlockSets'! "+this, new Error("error"));
				//throw new IllegalStateException("Starting a PutHandler thats not in 'waitingForBlockSets'! "+this);
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

		@Override
		public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
			if (logDEBUG) {
				//temp hack, ignored if called via super
				Throwable t = new Throwable("DEBUG onSuccess");
				StackTraceElement te = t.getStackTrace()[1];
				if (!("BaseManifestPutter.java".equals(te.getFileName()) && "onSuccess".equals(te.getMethodName()))) {
					Logger.error(this, "Not called via super", t);
				}
				//temp hack end
			}

			if (logMINOR) Logger.minor(this, "Completed '" + this.itemName + "' " + this);
			if (persistent) {
				container.activate(BaseManifestPutter.this, 1);
				container.activate(runningPutHandlers, 2);
			}

			if (putHandlersWaitingForFetchable.contains(this))
				BaseManifestPutter.this.onFetchable(this, container);

			ClientPutState oldState;
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
				if(putHandlersWaitingForMetadata.remove(this)) {
					if (persistent) {
						container.ext().store(putHandlersWaitingForMetadata, 2);
					}
					Logger.error(this, "PutHandler '"+this.itemName+"' was in waitingForMetadata in onSuccess() on "+this+" for "+BaseManifestPutter.this, new Error("debug"));
				}

				if(persistent) {
					container.deactivate(putHandlersWaitingForMetadata, 1);
					container.activate(putHandlerWaitingForBlockSets, 2);
				}
				if(putHandlerWaitingForBlockSets.remove(this)) {
					if(persistent) {
						container.ext().store(putHandlerWaitingForBlockSets, 2);
					}
					Logger.error(this, "PutHandler was in waitingForBlockSets in onSuccess() on "+this+" for "+BaseManifestPutter.this, new Error("debug"));
				}
				if(persistent) {
					container.deactivate(putHandlerWaitingForBlockSets, 1);
					container.deactivate(putHandlersWaitingForFetchable, 1);
					container.activate(putHandlersWaitingForFetchable, 2);
				}
				if(putHandlersWaitingForFetchable.remove(this)) {
					if (persistent) {
						container.ext().store(putHandlersWaitingForFetchable, 2);
					}
					Logger.error(this, "PutHandler was in waitingForFetchable in onSuccess() on "+this+" for "+BaseManifestPutter.this, new Error("debug"));
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
			tryComplete(container, context);

			if(persistent) {
				container.deactivate(runningPutHandlers, 1);
				container.deactivate(BaseManifestPutter.this, 1);
				removeFrom(container, context);
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
				container.activate(BaseManifestPutter.this, 1);
			fail(e, container, context);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}

		@Override
		public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
			throw new UnsupportedOperationException();
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
			throw new UnsupportedOperationException();
		}
		
		@Override
		public void onMetadata(Bucket meta, ClientPutState state,
				ObjectContainer container, ClientContext context) {
			throw new UnsupportedOperationException();
		}

		/** The number of blocks that will be needed to fetch the data. We put this in the top block metadata. */
		protected int minSuccessFetchBlocks;
		
		@Override
		public void addBlock(ObjectContainer container) {
			if(persistent) {
				container.activate(BaseManifestPutter.this, 1);
			}
			BaseManifestPutter.this.addBlock(container);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
			synchronized(this) {
				minSuccessFetchBlocks++;
			}
			super.addBlock(container);
		}

		@Override
		public void addBlocks(int num, ObjectContainer container) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.addBlocks(num, container);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
			synchronized(this) {
				minSuccessFetchBlocks+=num;
			}
			super.addBlocks(num, container);
		}

		@Override
		public void completedBlock(boolean dontNotify, ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.completedBlock(dontNotify, container, context);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
			super.completedBlock(dontNotify, container, context);
		}

		@Override
		public void failedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.failedBlock(container, context);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
			super.failedBlock(container, context);
		}

		@Override
		public void fatallyFailedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.fatallyFailedBlock(container, context);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
			super.fatallyFailedBlock(container, context);
		}

		@Override
		public synchronized void addMustSucceedBlocks(int blocks, ObjectContainer container) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.addMustSucceedBlocks(blocks, container);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
			synchronized(this) {
				minSuccessFetchBlocks += blocks;
			}
			super.addMustSucceedBlocks(blocks, container);
		}
		
		@Override
		public synchronized void addRedundantBlocks(int blocks, ObjectContainer container) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.addRedundantBlocks(blocks, container);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
			super.addRedundantBlocks(blocks, container);
		}
		
		@Override
		public synchronized int getMinSuccessFetchBlocks() {
			return minSuccessFetchBlocks;
		}
		
		@Override
		public void notifyClients(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(BaseManifestPutter.this, 1);
			BaseManifestPutter.this.notifyClients(container, context);
			if(persistent)
				container.deactivate(BaseManifestPutter.this, 1);
		}

		@Override
		public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(BaseManifestPutter.this, 1);
				container.activate(putHandlerWaitingForBlockSets, 2);
			}
			boolean allBlockSets = false;
			synchronized(BaseManifestPutter.this) {
				putHandlerWaitingForBlockSets.remove(this);
				if(persistent)
					container.ext().store(putHandlerWaitingForBlockSets, 2);
				if (freeformMode) {
					allBlockSets = hasResolvedBase && putHandlerWaitingForBlockSets.isEmpty();
				} else {
					allBlockSets = putHandlerWaitingForBlockSets.isEmpty();
				}
			}
			if(allBlockSets)
				BaseManifestPutter.this.blockSetFinalized(container, context);
			if(persistent) {
				container.deactivate(putHandlerWaitingForBlockSets, 1);
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

		@Override
		public void onFetchable(ClientPutState state, ObjectContainer container) {
			if(logMINOR) Logger.minor(this, "onFetchable " + this, new Exception("debug"));
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

		@Override
		public boolean objectCanNew(ObjectContainer container) {
			if(cancelled) {
				Logger.error(this, "Storing "+this+" when already cancelled!", new Exception("error"));
				return false;
			}
			if(logDEBUG) Logger.debug(this, "Storing "+this+" activated="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("debug"));
			return true;
		}

		@Override
		public String toString() {
			if (logDEBUG) return super.toString() + " {"+this.itemName+'}';
			return super.toString();
		}

		@Override
		protected void innerToNetwork(ObjectContainer container, ClientContext context) {
			// Ignore
		}
		
	}

	private final static String[] defaultDefaultNames =
		new String[] { "index.html", "index.htm", "default.html", "default.htm" };
	// All the default names are in the root.
	// Code will need to be changed if we have index/index.html or similar.
	
	/** if true top level metadata is a container */
	private boolean containerMode = false;
	/** if true top level metadata is a single chunk */
	private boolean freeformMode = false;

	/* common stuff, fields used in freeform and container mode */
	/** put is finalized if empty */
	private HashSet<PutHandler> putHandlerWaitingForBlockSets;
	/** if empty put is fetchable */
	private HashSet<PutHandler> putHandlersWaitingForFetchable;
	private HashSet<PutHandler> runningPutHandlers;

	// container stuff, all fields can be null'ed in freeform mode
	private ContainerBuilder rootContainerBuilder;
	private ContainerPutHandler rootContainerPutHandler;
	private HashSet<PutHandler> containerPutHandlers;
	private HashMap<PutHandler, HashSet<PutHandler>> perContainerPutHandlersWaitingForMetadata;
	/**
	 * PutHandler: the *PutHandler
	 * HashMap<String, Object>: the 'metadata dir' that contains the item inserted by PutHandler
	 * the *PutHandler fills in its result here (Metadata)
	 */
	private HashMap<PutHandler, HashMap<String, Object>> putHandlersTransformMap;
	private HashMap<ArchivePutHandler, Vector<PutHandler>> putHandlersArchiveTransformMap;

	// freeform stuff, all fields can be null'ed in container mode
	private FreeFormBuilder rootBuilder;
	private MetaPutHandler rootMetaPutHandler;
	private HashMap<String, Object> rootDir;
	private HashSet<PutHandler> putHandlersWaitingForMetadata;

	private FreenetURI finalURI;
	private final FreenetURI targetURI;
	private boolean finished;
	private final InsertContext ctx;
	final ClientPutCallback cb;
	private final boolean getCHKOnly;

	private int numberOfFiles;
	private long totalSize;
	private Metadata baseMetadata;
	private boolean hasResolvedBase; // if this is true, the final block is ready for insert
	private boolean fetchable;
	private final boolean earlyEncode;
	final byte[] forceCryptoKey;
	final byte cryptoAlgorithm;

	public BaseManifestPutter(ClientPutCallback cb,
			HashMap<String, Object> manifestElements, short prioClass, FreenetURI target, String defaultName,
			InsertContext ctx, boolean getCHKOnly2, RequestClient clientContext, boolean earlyEncode, boolean randomiseCryptoKeys, byte [] forceCryptoKey, ObjectContainer container, ClientContext context) throws TooManyFilesInsertException {
		super(prioClass, clientContext);
		if(client.persistent())
			this.targetURI = target.clone();
		else
			this.targetURI = target;
		this.cb = cb;
		this.ctx = ctx;
		this.getCHKOnly = getCHKOnly2;
		this.earlyEncode = earlyEncode;
		if(randomiseCryptoKeys && forceCryptoKey == null) {
			forceCryptoKey = new byte[32];
			context.random.nextBytes(forceCryptoKey);
		}
		this.forceCryptoKey = forceCryptoKey;
		
		if(client.persistent())
			container.activate(ctx, 1);
		CompatibilityMode mode = ctx.getCompatibilityMode();
		if(!(mode == CompatibilityMode.COMPAT_CURRENT || mode.ordinal() >= CompatibilityMode.COMPAT_1416.ordinal()))
			this.cryptoAlgorithm = Key.ALGO_AES_PCFB_256_SHA256;
		else
			this.cryptoAlgorithm = Key.ALGO_AES_CTR_256_SHA256;
		runningPutHandlers = new HashSet<PutHandler>();
		putHandlersWaitingForMetadata = new HashSet<PutHandler>();
		putHandlersWaitingForFetchable = new HashSet<PutHandler>();
		putHandlerWaitingForBlockSets = new HashSet<PutHandler>();
		containerPutHandlers = new HashSet<PutHandler>();
		perContainerPutHandlersWaitingForMetadata = new HashMap<PutHandler, HashSet<PutHandler>>();
		putHandlersTransformMap = new HashMap<PutHandler, HashMap<String, Object>>();
		putHandlersArchiveTransformMap = new HashMap<ArchivePutHandler, Vector<PutHandler>>();
		if(defaultName == null)
			defaultName = findDefaultName(manifestElements, defaultName);
		makePutHandlers(manifestElements, defaultName);
		// builders are not longer needed after constructor
		rootBuilder = null;
		rootContainerBuilder = null;
	}
	
	private String findDefaultName(HashMap<String, Object> manifestElements,
			String defaultName) {
		// Find the default name if it has not been set explicitly.
		for(String name : defaultDefaultNames) {
			Object o = manifestElements.get(name);
			if(o == null) continue;
			if(o instanceof HashMap) continue;
			return name;
		}
		for(String name : defaultDefaultNames) {
			boolean found = false;
			for(Map.Entry<String, Object> entry : manifestElements.entrySet()) {
				Object o = entry.getValue();
				if(o == null) continue;
				if(o instanceof HashMap) continue;
				if(entry.getKey().equalsIgnoreCase(name)) {
					found = true;
					name = entry.getKey();
					break;
				}
			}
			if(!found) continue;
			return name;
		}
		return "";
	}

	public void start(ObjectContainer container, ClientContext context) throws InsertException {
		if (logMINOR)
			Logger.minor(this, "Starting " + this+" persistence="+persistent()+ " containermode="+containerMode);
		PutHandler[] running;
		PutHandler[] containers;

		if(persistent()) {
			container.activate(runningPutHandlers, 2);
			if (containerMode)
				container.activate(putHandlerWaitingForBlockSets, 2);
		}
		synchronized (this) {
			running = runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
			if (containerMode) {
				containers = getContainersToStart(running.length > 0);
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
		//debugDecompose("Start - End");
	}

	private PutHandler[] getContainersToStart(boolean excludeRoot) {
		PutHandler[] maybeStartPH = containerPutHandlers.toArray(new PutHandler[containerPutHandlers.size()]);
		ArrayList<PutHandler> phToStart = new ArrayList<PutHandler>();

		for (PutHandler ph: maybeStartPH) {
			if (perContainerPutHandlersWaitingForMetadata.get(ph).isEmpty()) {
				phToStart.add(ph);
			}
		}
		if ((!excludeRoot) && (maybeStartPH.length == 0)) {
			phToStart.add(rootContainerPutHandler);
		}
		return phToStart.toArray(new PutHandler[phToStart.size()]);
	}

	/**
	 * Implement the pack logic.
	 *
	 * @param manifestElements A map from String to either ManifestElement or another String. This is the
	 * site structure, which will be split into containers and/or external inserts by the method.
	 * @throws TooManyFilesInsertException 
	 */
	protected abstract void makePutHandlers(HashMap<String, Object> manifestElements, String defaultName) throws TooManyFilesInsertException;

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
				// Impossible, we are already on the database thread
			}
			container.activate(BaseManifestPutter.this, 1);
			innerGotAllMetadata(container, context);
			container.deactivate(BaseManifestPutter.this, 1);
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
		if (containerMode) throw new IllegalStateException();
		if(persistent()) {
			container.activate(runGotAllMetadata, 1); // need to activate .this!
			try {
				context.jobRunner.queueRestartJob(runGotAllMetadata, NativeThread.NORM_PRIORITY, container, false);
				context.jobRunner.queue(runGotAllMetadata, NativeThread.NORM_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
				// Impossible, we are already on the database thread
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
		if (containerMode) throw new IllegalStateException();
		if(logMINOR) Logger.minor(this, "Got all metadata");
		baseMetadata = makeMetadata(rootDir, container);
		if(persistent()) {
			container.store(baseMetadata);
			container.store(this);
		}
		resolveAndStartBase(container, context);
	}

	@SuppressWarnings("unchecked")
	private Metadata makeMetadata(HashMap<String, Object> dir, ObjectContainer container) {
		SimpleManifestComposer smc = new SimpleManifestComposer();
		for(Map.Entry<String, Object> entry:dir.entrySet()) {
			String name = entry.getKey();
			Object item = entry.getValue();
			if (item == null) throw new NullPointerException();
			Metadata m;
			if (item instanceof HashMap) {
				if(persistent())
					container.activate(item, 2);
				m = makeMetadata((HashMap<String, Object>) item, container);
				if (m == null) throw new NullPointerException("HERE!!");
			} else {
				if (persistent()) {
					container.activate(item, 2);
				}
				m = ((PutHandler)item).metadata;
				if (m == null) throw new NullPointerException("HERE!!" +item);
				if (persistent()) {
					container.deactivate(item, 1);
				}
			}
			smc.addItem(name, m);
		}
		return smc.getMetadata();
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
		//new Error("DEBUG_ME_resolveAndStartBase").printStackTrace();
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
		}
		InsertBlock block;
		block = new InsertBlock(bucket, null, persistent() ? targetURI.clone() : targetURI);
		try {
			rootMetaPutHandler = new MetaPutHandler(this, null, block, getCHKOnly, container);

			if(logMINOR) Logger.minor(this, "Inserting main metadata: "+rootMetaPutHandler+" for "+baseMetadata);
			if(persistent()) {
				container.deactivate(baseMetadata, 1);
			}
			rootMetaPutHandler.start(container, context);
		} catch (InsertException e) {
			fail(e, container, context);
			return;
		}
		if(persistent()) {
			container.deactivate(rootMetaPutHandler, 1);
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
		new Error("RefactorME-resolve").printStackTrace();
		Metadata[] metas = e.mustResolve;
		for(Metadata m: metas) {
			if(persistent()) container.activate(m, 100);
			if(logMINOR) Logger.minor(this, "Resolving "+m);
			if(m.isResolved()) {
				Logger.error(this, "Already resolved: "+m+" in resolve() - race condition???");
				if(persistent()) container.deactivate(m, 1);
				continue;
			}
			try {

				MetaPutHandler ph = new MetaPutHandler(this, null, m, getCHKOnly, context.getBucketFactory(persistent()), container);
				ph.start(container, context);

				if(persistent()) {
					container.deactivate(ph, 1);
					container.deactivate(m, 1);
				}
			} catch (MetadataUnresolvedException e1) {
				resolve(e1, container, context);
				container.deactivate(m, 1);
			}
		}
	}

	private void tryComplete(ObjectContainer container, ClientContext context) {
		//debugDecompose("try complete");
		if(logDEBUG) Logger.debug(this, "try complete", new Error("trace tryComplete()"));
		synchronized(this) {
			if(finished || cancelled) {
				if(logMINOR) Logger.minor(this, "Already "+(finished?"finished":"cancelled"));
				if(persistent())
					container.store(this);
				return;
			}
			if (!runningPutHandlers.isEmpty()) {
				if (logDEBUG) Logger.debug(this, "Not finished, runningPutHandlers not empty.");
				return;
			}
			if (!containerPutHandlers.isEmpty()) {
				if (logDEBUG) Logger.debug(this, "Not finished, containerPutHandlers not empty.");
				return;
			}
			if (containerMode) {
				if (rootContainerPutHandler != null) {
					if (logDEBUG) Logger.debug(this, "Not finished, rootContainerPutHandler not empty.");
					return;
				}
			} else {
				if (rootMetaPutHandler != null) {
					if (logDEBUG) Logger.debug(this, "Not finished, rootMetaPutHandler not empty.");
					return;
				}
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

	private void fail(Exception e, ObjectContainer container, ClientContext context) {
		InsertException ie = new InsertException(InsertException.INTERNAL_ERROR, e, null);
		fail(ie, container, context);
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
		new Error("RefactorME").printStackTrace();
		container.activate(runningPutHandlers, 2);
		container.activate(putHandlersWaitingForMetadata, 2);
		container.activate(putHandlerWaitingForBlockSets, 2);
		container.activate(putHandlersWaitingForFetchable, 2);

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
		if(!putHandlerWaitingForBlockSets.isEmpty()) {
			Logger.error(this, "Put handlers waiting for block sets, not part of putHandlersByName: "+putHandlerWaitingForBlockSets.size()+" in removePutHandlers() on "+this, new Exception("error"));
			PutHandler[] handlers = putHandlerWaitingForBlockSets.toArray(new PutHandler[putHandlerWaitingForBlockSets.size()]);
			for(PutHandler handler : handlers) {
				container.activate(handler, 1);
				Logger.error(this, "Still waiting for block set, but not in putHandlersByName: "+handler);
				handler.cancel();
				handler.removeFrom(container, context);
			}
			putHandlerWaitingForBlockSets.clear();
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
		container.delete(putHandlerWaitingForBlockSets);
		container.delete(putHandlersWaitingForFetchable);
		runningPutHandlers = null;
		putHandlersWaitingForMetadata = null;
		putHandlerWaitingForBlockSets = null;
		putHandlersWaitingForFetchable = null;
		container.store(this);
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
		// TODO
//		ClientPutState[] runningMeta;
//		if(persistent())
//			container.activate(metadataPuttersByMetadata, 2);
//		synchronized(this) {
//			runningMeta = metadataPuttersByMetadata.values().toArray(new ClientPutState[metadataPuttersByMetadata.size()]);
//		}
//
//		if(logMINOR) Logger.minor(this, "Metadata putters to cancel: "+runningMeta.length);
//		for(ClientPutState putter : runningMeta) {
//			boolean active = true;
//			if(persistent) {
//				active = container.ext().isActive(putter);
//				if(!active) container.activate(putter, 1);
//			}
//			putter.cancel(container, context);
//			if(!active) container.deactivate(putter, 1);
//			if(persistent) container.activate(this, 1);
//		}
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
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, minSuccessFetchBlocks, this.blockSetFinalized), container, context);
	}

	@Override
	public int getMinSuccessFetchBlocks() {
		return minSuccessFetchBlocks;
	}
	
	@Override
	public void blockSetFinalized(ObjectContainer container, ClientContext context) {
		if(persistent())
			container.deactivate(putHandlerWaitingForBlockSets, 1);
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
		//new Error("Trace_ME onFetchable").printStackTrace();
		if(persistent()) {
			container.activate(putHandlersWaitingForFetchable, 2);
		}
		if(checkFetchable(handler)) {
			if(persistent()) {
				container.ext().store(putHandlersWaitingForMetadata, 2);
				container.store(this);
				container.deactivate(putHandlersWaitingForFetchable, 1);
				container.activate(cb, 1);
			}
			cb.onFetchable(this, container);
			if(persistent())
				container.deactivate(cb, 1);
		} else {
			if(persistent()) {
				container.deactivate(putHandlersWaitingForFetchable, 1);
			}
		}
	}

	private synchronized boolean checkFetchable(PutHandler handler) {
		//new Error("RefactorME").printStackTrace();
		if (!putHandlersWaitingForFetchable.remove(handler)) {
			throw new IllegalStateException("was not in putHandlersWaitingForFetchable! : "+handler);
		}
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
	public void onTransition(ClientPutState from, ClientPutState to, ObjectContainer container) {
		// Ignore
	}

	@Override
	protected void innerToNetwork(ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		if (logDEBUG) Logger.debug(this, "removeFrom", new Exception("debug"));
		if(finalURI != null) {
			container.activate(finalURI, 5);
			finalURI.removeFrom(container);
		}
		container.activate(targetURI, 5);
		targetURI.removeFrom(container);
		// This is passed in. We should not remove it, because the caller (ClientPutDir) should remove it.
		container.activate(ctx, 1);
		ctx.removeFrom(container);
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
		//Logger.error(this, "Updating "+this+" activated="+container.ext().isActive(this)+" stored="+container.ext().isStored(this), new Exception("debug"));
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

	private void tryStartParentContainer(PutHandler containerHandle2, ObjectContainer container, ClientContext context) throws InsertException {
		//new Error("RefactorME").printStackTrace();
		if (containerHandle2 == null) throw new NullPointerException();
		//if (perContainerPutHandlersWaitingForMetadata.get(containerHandle2).isEmpty() && perContainerPutHandlersWaitingForFetchable.get(containerHandle2).isEmpty()) {
		if (perContainerPutHandlersWaitingForMetadata.get(containerHandle2).isEmpty()) {
			perContainerPutHandlersWaitingForMetadata.remove(containerHandle2);
			if (persistent()) {
				container.ext().store(perContainerPutHandlersWaitingForMetadata, 2);
			}
			containerHandle2.start(container, context);
		} else {
			//System.out.println(" waiting m:"+perContainerPutHandlersWaitingForMetadata.get(containerHandle2).size()+" F:"+perContainerPutHandlersWaitingForFetchable.get(containerHandle2).size() + " for "+containerHandle2);
			if(logMINOR)
				Logger.minor(this, "(spc) waiting m:"+perContainerPutHandlersWaitingForMetadata.get(containerHandle2).size() + " for "+containerHandle2);
		}
	}

	// compose helper stuff

	protected final ClientMetadata guessMime(String name, ManifestElement me) {
		return guessMime(name, me.mimeOverride);
	}

	protected final ClientMetadata guessMime(String name, String mimetype) {
		String mimeType = mimetype;
		if((mimeType == null) && (name != null))
			mimeType = DefaultMIMETypes.guessMIMEType(name, true);
		ClientMetadata cm;
		if(mimeType == null || mimeType.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE))
			cm = null;
		else
			cm = new ClientMetadata(mimeType);
		return cm;
	}

	public ContainerBuilder makeArchive() {
		return new ContainerBuilder(false, null, null, true);
	}

	protected ContainerBuilder getRootContainer() {
		if (freeformMode) throw new IllegalStateException("Already in freeform mode!");
		if (!containerMode) {
			containerMode = true;
			rootContainerBuilder = new ContainerBuilder(true);
		}
		return rootContainerBuilder;
	}

	protected FreeFormBuilder getRootBuilder() {
		if (containerMode) throw new IllegalStateException("Already in container mode!");
		if (!freeformMode) {
			freeformMode = true;
			rootBuilder = new FreeFormBuilder();
		}
		return rootBuilder;
	}

	protected abstract class ManifestBuilder {

		private final Stack<HashMap<String, Object>> dirStack;
		/** Map from name to either a Metadata (to be included as-is), a ManifestElement (either a redirect
		 * or a file), or another HashMap. Eventually processed by e.g. ContainerInserter.makeManifest()
		 * (for a ContainerBuilder). */
		protected HashMap<String, Object> currentDir;

		private ClientMetadata makeClientMetadata(String mime) {
			if (mime == null)
				return null;
			ClientMetadata cm = new ClientMetadata(mime.trim());
			if (cm.isTrivial())
				return null;
			return cm;
		}

		ManifestBuilder() {
			dirStack = new Stack<HashMap<String, Object>>();
		}

		public void pushCurrentDir() {
			dirStack.push(currentDir);
		}

		public void popCurrentDir() {
			currentDir = dirStack.pop();
		}

		/**
		 * make 'name' the current subdir (cd into it).<br>
		 * if it not exists, it is created.
		 *
		 * @param name name of the subdir
		 */
		public void makeSubDirCD(String name) {
			Object dir = currentDir.get(name);
			if (dir != null) {
				currentDir = Metadata.forceMap(dir);
			} else {
				currentDir = makeSubDir(currentDir, name);
			}
		}

		private HashMap<String, Object> makeSubDir(HashMap<String, Object> parentDir, String name) {
			if (parentDir.containsKey(name)) {
				throw new IllegalStateException("Item '"+name+"' already exist!");
			}
			HashMap<String, Object> newDir = new HashMap<String, Object>();
			parentDir.put(name , newDir);
			return newDir;
		}

		/**
		 * add a ManifestElement, either a redirect (target uri given) or an external
		 * @param name
		 * @param element
		 * @param isDefaultDoc
		 */
		public final void addElement(String name, ManifestElement element, boolean isDefaultDoc) {
			ClientMetadata cm = makeClientMetadata(element.mimeOverride);

			if (element.data != null) {
				addExternal(name, element.data, cm, isDefaultDoc);
				return;
			}
			if (element.targetURI != null) {
				addRedirect(name, element.targetURI, cm, isDefaultDoc);
				return;
			}
			throw new IllegalStateException("ME is neither a redirect nor dircet data. "+element);
		}

		/** Add a file as an external. It will be inserted separately and we will add a redirect to the
		 * metadata.
		 * @param name The name of the file (short name within the original folder, it's not in a container).
		 * @param data The data to be inserted.
		 * @param mimeOverride Optional MIME type override.
		 * @param isDefaultDoc If true, make this the default document.
		 */
		public final void addExternal(String name, Bucket data, String mimeOverride, boolean isDefaultDoc) {
			assert(data != null);
			ClientMetadata cm = makeClientMetadata(mimeOverride);
			addExternal(name, data, cm, isDefaultDoc);
		}

		public final void addRedirect(String name, FreenetURI targetUri, String mimeOverride, boolean isDefaultDoc) {
			ClientMetadata cm = makeClientMetadata(mimeOverride);
			addRedirect(name, targetUri, cm, isDefaultDoc);
		}

		public abstract void addExternal(String name, Bucket data, ClientMetadata cm, boolean isDefaultDoc);
		public abstract void addRedirect(String name, FreenetURI targetUri, ClientMetadata cm, boolean isDefaultDoc);
	}

	protected final class FreeFormBuilder extends ManifestBuilder {

		protected FreeFormBuilder() {
			rootDir = new HashMap<String, Object>();
			currentDir = rootDir;
		}

		@Override
		public void addExternal(String name, Bucket data, ClientMetadata cm, boolean isDefaultDoc) {
			PutHandler ph;
			ph = new ExternPutHandler(BaseManifestPutter.this, null, name, data, cm, getCHKOnly);
//			putHandlersWaitingForMetadata.add(ph);
//			putHandlersWaitingForFetchable.add(ph);
			if(logMINOR) Logger.minor(this, "Inserting separately as PutHandler: "+name+" : "+ph+" persistent="+ph.persistent());
			numberOfFiles++;
			totalSize += data.size();
			currentDir.put(name, ph);
			if (isDefaultDoc) {
				ph = new JokerPutHandler(BaseManifestPutter.this, null, name, name);
				currentDir.put("", ph);
			}
		}

		@Override
		public void addRedirect(String name, FreenetURI targetURI2, ClientMetadata cm, boolean isDefaultDoc) {
			PutHandler ph;
			ph = new JokerPutHandler(BaseManifestPutter.this, name, targetURI2, cm);
			currentDir.put(name, ph);
			if (isDefaultDoc)
				currentDir.put("", ph);
		}
	}

	protected final class ContainerBuilder extends ManifestBuilder {

		private final HashMap<String, Object> _rootDir;
		private final PutHandler selfHandle;

		private ContainerBuilder(boolean isRoot) {
			this(isRoot, null, null, false);
		}

		private ContainerBuilder(PutHandler parent, String name) {
			this(false, parent, name, false);
		}

		private ContainerBuilder(boolean isRoot, PutHandler parent, String name, boolean isArchive) {
			if (!containerMode) {
				throw new IllegalStateException("You can not add containers in free form mode!");
			}
			_rootDir = new HashMap<String, Object>();
			if (isArchive)
				selfHandle = new ArchivePutHandler(BaseManifestPutter.this,
						parent, name, _rootDir,
						(isRoot ? BaseManifestPutter.this.targetURI
								: FreenetURI.EMPTY_CHK_URI), getCHKOnly);
			else
				selfHandle = new ContainerPutHandler(BaseManifestPutter.this,
						parent, name, _rootDir,
						(isRoot ? BaseManifestPutter.this.targetURI
								: FreenetURI.EMPTY_CHK_URI), null, getCHKOnly, (isRoot ? null : containerPutHandlers));
			currentDir = _rootDir;
			if (isRoot) {
				rootContainerPutHandler = (ContainerPutHandler)selfHandle;
			} else {
				containerPutHandlers.add(selfHandle);
			}
			perContainerPutHandlersWaitingForMetadata.put(selfHandle, new HashSet<PutHandler>());
			//perContainerPutHandlersWaitingForFetchable.put(selfHandle, new HashSet<PutHandler>());
			if (isArchive) putHandlersArchiveTransformMap.put((ArchivePutHandler)selfHandle, new Vector<PutHandler>());
		}

		public ContainerBuilder makeSubContainer(String name) {
			ContainerBuilder subCon = new ContainerBuilder(selfHandle, name);
			currentDir.put(name , subCon.selfHandle);
			putHandlersTransformMap.put(subCon.selfHandle, currentDir);
			perContainerPutHandlersWaitingForMetadata.get(selfHandle).add(subCon.selfHandle);
			return subCon;
		}

		/**
		 * Add a ManifestElement, which can be a file in an archive, or a redirect.
		 * @param name The original name of the file (e.g. index.html).
		 * @param nameInArchive The fully qualified name of the file in the archive (e.g. testing/index.html).
		 * @param element The ManifestElement specifying the data, redirect, etc. Note that redirects are
		 * still included in containers, both for structural reasons and because the metadata can be large
		 * enough that we need to split it.
		 * @param isDefaultDoc If true, add a link from "" to this element, making it the default document
		 * in this container.
		 */
		public void addItem(String name, String nameInArchive, ManifestElement element, boolean isDefaultDoc) {
			ManifestElement me = new ManifestElement(element, name, nameInArchive);
			addItem(name, me, isDefaultDoc);
		}

		private void addItem(String name, ManifestElement element, boolean isDefaultDoc) {
			currentDir.put(name, element);
			if (isDefaultDoc) {
				Metadata m = new Metadata(Metadata.SYMBOLIC_SHORTLINK, null, null, name, null);
				currentDir.put("", m);
			}
		}

		@Override
		public void addRedirect(String name, FreenetURI targetUri, ClientMetadata cm, boolean isDefaultDoc) {
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, targetUri, cm);
			currentDir.put(name, m);
			if (isDefaultDoc) {
				currentDir.put("", m);
			}
		}

		@Override
		public void addExternal(String name, Bucket data, ClientMetadata cm, boolean isDefaultDoc) {
			PutHandler ph = new ExternPutHandler(BaseManifestPutter.this, selfHandle, name, data, cm, getCHKOnly);
			perContainerPutHandlersWaitingForMetadata.get(selfHandle).add(ph);
			putHandlersTransformMap.put(ph, currentDir);
			if (isDefaultDoc) {
				Metadata m = new Metadata(Metadata.SYMBOLIC_SHORTLINK, null, null, name, null);
				currentDir.put("", m);
			}
		}

		/** FIXME what is going on here? Why do we need to add a JokerPutHandler, when a lot of code just
		 * calls addItem()? */
		public void addArchiveItem(ContainerBuilder archive, String name, ManifestElement element, boolean isDefaultDoc) {
			assert(element.getData() != null);
			archive.addItem(name, element, false);
			PutHandler ph = new JokerPutHandler(BaseManifestPutter.this, selfHandle, name, guessMime(name, element.mimeOverride));
			putHandlersTransformMap.put(ph, currentDir);
			perContainerPutHandlersWaitingForMetadata.get(selfHandle).add(ph);
			putHandlersArchiveTransformMap.get(archive.selfHandle).add(ph);
			if (isDefaultDoc) {
				Metadata m = new Metadata(Metadata.SYMBOLIC_SHORTLINK, null, null, name, null);
				currentDir.put("", m);
			}
		}
	}
	
}
