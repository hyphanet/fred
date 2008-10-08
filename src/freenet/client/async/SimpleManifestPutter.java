package freenet.client.async;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

public class SimpleManifestPutter extends BaseClientPutter implements PutCompletionCallback {
	// Only implements PutCompletionCallback for the final metadata insert

	private class PutHandler extends BaseClientPutter implements PutCompletionCallback {
		
		protected PutHandler(final SimpleManifestPutter smp, String name, Bucket data, ClientMetadata cm, boolean getCHKOnly) {
			super(smp.priorityClass, smp.client);
			this.persistent = SimpleManifestPutter.this.persistent();
			this.cm = cm;
			this.data = data;
			InsertBlock block = 
				new InsertBlock(data, cm, FreenetURI.EMPTY_CHK_URI);
			this.origSFI =
				new SingleFileInserter(this, this, block, false, ctx, false, getCHKOnly, true, null, false, false, null, earlyEncode);
			metadata = null;
		}

		protected PutHandler(final SimpleManifestPutter smp, String name, FreenetURI target, ClientMetadata cm) {
			super(smp.getPriorityClass(), smp.client);
			this.persistent = SimpleManifestPutter.this.persistent();
			this.cm = cm;
			this.data = null;
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, target, cm);
			metadata = m;
			origSFI = null;
		}
		
		protected PutHandler(final SimpleManifestPutter smp, String name, String targetInZip, ClientMetadata cm, Bucket data) {
			super(smp.getPriorityClass(), smp.client);
			this.persistent = SimpleManifestPutter.this.persistent();
			this.cm = cm;
			this.data = data;
			this.targetInZip = targetInZip;
			Metadata m = new Metadata(Metadata.ZIP_INTERNAL_REDIRECT, targetInZip, cm);
			metadata = m;
			origSFI = null;
		}
		
		private SingleFileInserter origSFI;
		private ClientMetadata cm;
		private Metadata metadata;
		private String targetInZip;
		private final Bucket data;
		private final boolean persistent;
		
		public void start(ObjectContainer container, ClientContext context) throws InsertException {
			if (origSFI == null) {
				 Logger.error(this, "origSFI is null on start(), should be impossible", new Exception("debug"));
				 return;
			}
			if (metadata != null) {
				Logger.error(this, "metdata=" + metadata + " on start(), should be impossible", new Exception("debug"));
				return;
			}
			if(persistent)
				container.activate(origSFI, 1);
			origSFI.start(null, container, context);
			origSFI = null;
			if(persistent)
				container.store(this);
		}
		
		public void cancel(ObjectContainer container, ClientContext context) {
			super.cancel();
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
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Completed "+this);
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
				container.activate(runningPutHandlers, 2);
			}
			SimpleManifestPutter.this.onFetchable(this, container);
			synchronized(SimpleManifestPutter.this) {
				runningPutHandlers.remove(this);
				if(persistent)
					container.store(runningPutHandlers);
				if(!runningPutHandlers.isEmpty()) {
					if(logMINOR) {
						Logger.minor(this, "Running put handlers: "+runningPutHandlers.size());
//						for(Object o : runningPutHandlers) {
//							boolean activated = true;
//							if(persistent) {
//								activated = container.ext().isActive(o);
//								if(!activated) container.activate(o, 1);
//							}
//							Logger.minor(this, "Still running: "+o);
//							if(!activated)
//								container.deactivate(o, 1);
//						}
					}
					return;
				}
			}
			insertedAllFiles(container);
		}

		public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Failed: "+this+" - "+e, e);
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			fail(e, container);
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
					new Metadata(Metadata.SIMPLE_REDIRECT, key.getURI(), cm);
				onMetadata(m, null, container, context);
			}
		}

		public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {}

		public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Assigning metadata: "+m+" for "+this+" from "+state+" persistent="+persistent,
					new Exception("debug"));
			if(metadata != null) {
				Logger.error(this, "Reassigning metadata", new Exception("debug"));
				return;
			}
			metadata = m;
			if(persistent) {
				container.activate(putHandlersWaitingForMetadata, 1);
			}
			synchronized(SimpleManifestPutter.this) {
				putHandlersWaitingForMetadata.remove(this);
				if(persistent) {
					container.store(putHandlersWaitingForMetadata);
					container.store(this);
				}
				if(!putHandlersWaitingForMetadata.isEmpty()) return;
			}
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
			}
			gotAllMetadata(container, context);
		}

		@Override
		public void addBlock(ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.addBlock(container);
		}
		
		@Override
		public void addBlocks(int num, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.addBlocks(num, container);
		}
		
		@Override
		public void completedBlock(boolean dontNotify, ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.completedBlock(dontNotify, container, context);
		}
		
		@Override
		public void failedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.failedBlock(container, context);
		}
		
		@Override
		public void fatallyFailedBlock(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.fatallyFailedBlock(container, context);
		}
		
		@Override
		public void addMustSucceedBlocks(int blocks, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.addMustSucceedBlocks(blocks, container);
		}
		
		@Override
		public void notifyClients(ObjectContainer container, ClientContext context) {
			// FIXME generate per-filename events???
		}

		public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(SimpleManifestPutter.this, 1);
				container.activate(waitingForBlockSets, 1);
			}
			synchronized(SimpleManifestPutter.this) {
				waitingForBlockSets.remove(this);
				if(persistent)
					container.store(waitingForBlockSets);
				if(!waitingForBlockSets.isEmpty()) return;
			}
			SimpleManifestPutter.this.blockSetFinalized(container, context);
		}

		@Override
		public void onMajorProgress(ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.onMajorProgress(container);
		}

		public void onFetchable(ClientPutState state, ObjectContainer container) {
			if(persistent)
				container.activate(SimpleManifestPutter.this, 1);
			SimpleManifestPutter.this.onFetchable(this, container);
		}

		@Override
		public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
			// Ignore
		}

	}

	static boolean logMINOR;
	private final HashMap putHandlersByName;
	private final HashSet runningPutHandlers;
	private final HashSet putHandlersWaitingForMetadata;
	private final HashSet waitingForBlockSets;
	private final HashSet putHandlersWaitingForFetchable;
	private FreenetURI finalURI;
	private FreenetURI targetURI;
	private boolean finished;
	private final InsertContext ctx;
	final ClientCallback cb;
	private final boolean getCHKOnly;
	private boolean insertedAllFiles;
	private boolean insertedManifest;
	private final HashMap metadataPuttersByMetadata;
	private final HashMap metadataPuttersUnfetchable;
	private final String defaultName;
	private int numberOfFiles;
	private long totalSize;
	private boolean metadataBlockSetFinalized;
	private Metadata baseMetadata;
	private boolean hasResolvedBase;
	private final static String[] defaultDefaultNames =
		new String[] { "index.html", "index.htm", "default.html", "default.htm" };
	private int bytesOnZip;
	private Vector elementsToPutInZip;
	private boolean fetchable;
	private final boolean earlyEncode;
	
	public SimpleManifestPutter(ClientCallback cb, 
			HashMap manifestElements, short prioClass, FreenetURI target, 
			String defaultName, InsertContext ctx, boolean getCHKOnly, RequestClient clientContext, boolean earlyEncode) {
		super(prioClass, clientContext);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.defaultName = defaultName;
		this.targetURI = target;
		this.cb = cb;
		this.ctx = ctx;
		this.getCHKOnly = getCHKOnly;
		this.earlyEncode = earlyEncode;
		putHandlersByName = new HashMap();
		runningPutHandlers = new HashSet();
		putHandlersWaitingForMetadata = new HashSet();
		putHandlersWaitingForFetchable = new HashSet();
		waitingForBlockSets = new HashSet();
		metadataPuttersByMetadata = new HashMap();
		metadataPuttersUnfetchable = new HashMap();
		elementsToPutInZip = new Vector();
		makePutHandlers(manifestElements, putHandlersByName);
		checkZips();
	}

	private void checkZips() {
		// If there are too few files in the zip, then insert them directly instead.
		// FIXME do something.
	}

	public void start(ObjectContainer container, ClientContext context) throws InsertException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if (logMINOR)
			Logger.minor(this, "Starting " + this+" persistence="+persistent());
		PutHandler[] running;

		if(persistent()) {
			container.activate(runningPutHandlers, 2);
		}
		synchronized (this) {
			running = (PutHandler[]) runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
		}
		try {
			for (int i = 0; i < running.length; i++) {
				running[i].start(container, context);
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
			cancelAndFinish(container);
			throw e;
		}
	}
	
	private void makePutHandlers(HashMap manifestElements, HashMap putHandlersByName) {
		makePutHandlers(manifestElements, putHandlersByName, "/");
	}
	
	private void makePutHandlers(HashMap manifestElements, HashMap putHandlersByName, String ZipPrefix) {
		Iterator it = manifestElements.keySet().iterator();
		while(it.hasNext()) {
			String name = (String) it.next();
			Object o = manifestElements.get(name);
			if(o instanceof HashMap) {
				HashMap subMap = new HashMap();
				putHandlersByName.put(name, subMap);
				makePutHandlers((HashMap)o, subMap, ZipPrefix+name+ '/');
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
					ph = new PutHandler(this, name, element.targetURI, cm);
					// Just a placeholder, don't actually run it
				} else {
					// Decide whether to put it in the ZIP.
					// FIXME support multiple ZIPs and size limits.
					// FIXME support better heuristics.
					int sz = (int)data.size() + 40 + element.fullName.length();
					if((data.size() <= 65536) && 
							(bytesOnZip + sz < ((2048-64)*1024))) { // totally dumb heuristic!
						bytesOnZip += sz;
						// Put it in the zip.
						if(logMINOR)
							Logger.minor(this, "Putting into ZIP: "+name);
						ph = new PutHandler(this, name, ZipPrefix+element.fullName, cm, data);
						elementsToPutInZip.add(ph);
						numberOfFiles++;
						totalSize += data.size();
					} else {
							ph = new PutHandler(this,name, data, cm, getCHKOnly);
						runningPutHandlers.add(ph);
						putHandlersWaitingForMetadata.add(ph);
						putHandlersWaitingForFetchable.add(ph);
						if(logMINOR)
							Logger.minor(this, "Inserting separately as PutHandler: "+name+" : "+ph+" persistent="+ph.persistent()+":"+ph.persistent);
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

	private void gotAllMetadata(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(putHandlersByName, 1);
		}
		if(logMINOR) Logger.minor(this, "Got all metadata");
		HashMap namesToByteArrays = new HashMap();
		namesToByteArrays(putHandlersByName, namesToByteArrays, container);
		if(defaultName != null) {
			Metadata meta = (Metadata) namesToByteArrays.get(defaultName);
			if(meta == null) {
				fail(new InsertException(InsertException.INVALID_URI, "Default name "+defaultName+" does not exist", null), container);
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
	
	private void resolveAndStartBase(ObjectContainer container, ClientContext context) {
		Bucket bucket = null;
		synchronized(this) {
			if(hasResolvedBase) return;
		}
		while(true) {
			try {
				bucket = BucketTools.makeImmutableBucket(context.getBucketFactory(persistent()), baseMetadata.writeToByteArray());
				break;
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container);
				return;
			} catch (MetadataUnresolvedException e) {
				try {
					resolve(e, container, context);
				} catch (IOException e1) {
					fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container);
					return;
				} catch (InsertException e2) {
					fail(e2, container);
					return;
				}
			}
		}
		if(bucket == null) return;
		synchronized(this) {
			if(hasResolvedBase) return;
			hasResolvedBase = true;
		}
		if(persistent())
			container.store(this);
		InsertBlock block;
		boolean isMetadata = true;
		boolean insertAsArchiveManifest = false;
		if(!(elementsToPutInZip.isEmpty())) {
			// There is a zip to insert.
			// We want to include the metadata.
			// We have the metadata, fortunately enough, because everything has been resolve()d.
			// So all we need to do is create the actual ZIP.
			try {
				
				// FIXME support formats other than .zip.
				// Only the *decoding* is generic at present.
				
				Bucket zipBucket = context.getBucketFactory(persistent()).makeBucket(baseMetadata.dataLength());
				OutputStream os = new BufferedOutputStream(zipBucket.getOutputStream());
				ZipOutputStream zos = new ZipOutputStream(os);
				ZipEntry ze;
				
				if(persistent()) {
					container.activate(elementsToPutInZip, 2);
				}
				for(Iterator i=elementsToPutInZip.iterator();i.hasNext();) {
					PutHandler ph = (PutHandler) i.next();
					ze = new ZipEntry(ph.targetInZip);
					ze.setTime(0);
					zos.putNextEntry(ze);
					if(persistent())
						container.activate(ph.data, 5);
					BucketTools.copyTo(ph.data, zos, ph.data.size());
				}
				
				// Add .metadata - after the rest.
				ze = new ZipEntry(".metadata");
				ze.setTime(0); // -1 = now, 0 = 1970.
				zos.putNextEntry(ze);
				BucketTools.copyTo(bucket, zos, bucket.size());
				
				zos.closeEntry();
				// Both finish() and close() are necessary.
				zos.finish();
				zos.flush();
				zos.close();
				
				// Now we have to insert the ZIP.
				
				// Can we just insert it, and not bother with a redirect to it?
				// Thereby exploiting implicit manifest support, which will pick up on .metadata??
				// We ought to be able to !!
				block = new InsertBlock(zipBucket, new ClientMetadata("application/zip"), targetURI);
				isMetadata = false;
				insertAsArchiveManifest = true;
			} catch (ZipException e) {
				fail(new InsertException(InsertException.INTERNAL_ERROR, e, null), container);
				return;
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null), container);
				return;
			}
		} else
			block = new InsertBlock(bucket, null, targetURI);
		try {
			SingleFileInserter metadataInserter = 
				new SingleFileInserter(this, this, block, isMetadata, ctx, false, getCHKOnly, false, baseMetadata, insertAsArchiveManifest, true, null, earlyEncode);
			if(logMINOR) Logger.minor(this, "Inserting main metadata: "+metadataInserter);
			if(persistent()) {
				container.activate(metadataPuttersByMetadata, 2);
				container.activate(metadataPuttersUnfetchable, 2);
			}
			this.metadataPuttersByMetadata.put(baseMetadata, metadataInserter);
			metadataPuttersUnfetchable.put(baseMetadata, metadataInserter);
			metadataInserter.start(null, container, context);
			if(persistent()) {
				container.store(metadataPuttersByMetadata);
				container.store(metadataPuttersUnfetchable);
			}
		} catch (InsertException e) {
			fail(e, container);
		}
	}

	private boolean resolve(MetadataUnresolvedException e, ObjectContainer container, ClientContext context) throws InsertException, IOException {
		Metadata[] metas = e.mustResolve;
		boolean mustWait = false;
		if(persistent())
			container.activate(metadataPuttersByMetadata, 2);
		for(int i=0;i<metas.length;i++) {
			Metadata m = metas[i];
			if(!m.isResolved())
				mustWait = true;
			synchronized(this) {
				if(metadataPuttersByMetadata.containsKey(m)) continue;
			}
			try {
				Bucket b = m.toBucket(context.getBucketFactory(persistent()));
				
				InsertBlock ib = new InsertBlock(b, null, FreenetURI.EMPTY_CHK_URI);
				SingleFileInserter metadataInserter = 
					new SingleFileInserter(this, this, ib, true, ctx, false, getCHKOnly, false, m, false, true, null, earlyEncode);
				if(logMINOR) Logger.minor(this, "Inserting subsidiary metadata: "+metadataInserter+" for "+m);
				synchronized(this) {
					this.metadataPuttersByMetadata.put(m, metadataInserter);
				}
				metadataInserter.start(null, container, context);
			} catch (MetadataUnresolvedException e1) {
				resolve(e1, container, context);
			}
		}
		if(persistent())
			container.store(metadataPuttersByMetadata);
		return mustWait;
	}

	private void namesToByteArrays(HashMap putHandlersByName, HashMap namesToByteArrays, ObjectContainer container) {
		Iterator i = putHandlersByName.keySet().iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			Object o = putHandlersByName.get(name);
			if(o instanceof PutHandler) {
				PutHandler ph = (PutHandler) o;
				if(persistent())
					container.activate(ph, 1);
				Metadata meta = ph.metadata;
				if(ph.metadata == null)
					Logger.error(this, "Metadata for "+name+" : "+ph+" is null");
				if(persistent())
					container.activate(meta, 100);
				Logger.minor(this, "Putting "+name);
				namesToByteArrays.put(name, meta);
			} else if(o instanceof HashMap) {
				HashMap subMap = new HashMap();
				if(persistent())
					container.activate(o, 1);
				namesToByteArrays.put(name, subMap);
				Logger.minor(this, "Putting directory: "+name);
				namesToByteArrays((HashMap)o, subMap, container);
			} else
				throw new IllegalStateException();
		}
	}

	private void insertedAllFiles(ObjectContainer container) {
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
		complete(container);
	}
	
	private void complete(ObjectContainer container) {
		if(persistent())
			container.activate(cb, 1);
		cb.onSuccess(this, container);
	}

	private void fail(InsertException e, ObjectContainer container) {
		// Cancel all, then call the callback
		cancelAndFinish(container);
		
		if(persistent())
			container.activate(cb, 1);
		cb.onFailure(e, this, container);
	}
	
	/**
	 * Cancel all running inserters and set finished to true.
	 */
	private void cancelAndFinish(ObjectContainer container) {
		PutHandler[] running;
		if(persistent())
			container.activate(runningPutHandlers, 2);
		synchronized(this) {
			if(finished) return;
			running = (PutHandler[]) runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
			finished = true;
		}
		if(persistent())
			container.store(this);
		
		for(int i=0;i<running.length;i++) {
			running[i].cancel();
		}
	}
	
	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		super.cancel();
		if(persistent())
			container.store(this);
		fail(new InsertException(InsertException.CANCELLED), container);
	}
	
	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(persistent()) {
			container.activate(metadataPuttersByMetadata, 2);
		}
		synchronized(this) {
			metadataPuttersByMetadata.remove(state.getToken());
			if(!metadataPuttersByMetadata.isEmpty()) {
				if(logMINOR) Logger.minor(this, "Still running metadata putters: "+metadataPuttersByMetadata.size());
				return;
			}
			Logger.minor(this, "Inserted manifest successfully on "+this);
			insertedManifest = true;
			if(finished) {
				if(logMINOR) Logger.minor(this, "Already finished");
				if(persistent())
					container.store(this);
				return;
			}
			if(!insertedAllFiles) {
				if(logMINOR) Logger.minor(this, "Not inserted all files");
				if(persistent())
					container.store(this);
				return;
			}
			finished = true;
		}
		if(persistent()) {
			container.store(metadataPuttersByMetadata);
			container.store(this);
		}
		complete(container);
	}
	
	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		fail(e, container);
	}
	
	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(state.getToken() == baseMetadata) {
			this.finalURI = key.getURI();
			if(logMINOR) Logger.minor(this, "Got metadata key: "+finalURI);
			if(persistent())
				container.activate(cb, 1);
			cb.onGeneratedURI(finalURI, this, container);
			if(persistent())
				container.store(this);
		} else {
			// It's a sub-Metadata
			Metadata m = (Metadata) state.getToken();
			m.resolve(key.getURI());
			if(logMINOR) Logger.minor(this, "Resolved "+m+" : "+key.getURI());
			resolveAndStartBase(container, context);
		}
	}
	
	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		synchronized(this) {
			if(logMINOR) Logger.minor(this, "Transition: "+oldState+" -> "+newState);
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
			if(!waitingForBlockSets.isEmpty()) {
				if(persistent())
					container.store(this);
				return;
			}
		}
		this.blockSetFinalized(container, context);
		if(persistent())
			container.store(this);
	}

	@Override
	public void blockSetFinalized(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(!metadataBlockSetFinalized) return;
			if(waitingForBlockSets.isEmpty()) return;
		}
		super.blockSetFinalized(container, context);
		if(persistent())
			container.store(this);
	}
	
	/**
	 * Convert a HashMap of name -> bucket to a HashSet of ManifestEntry's.
	 * All are to have mimeOverride=null, i.e. we use the auto-detected mime type
	 * from the filename.
	 */
	public static HashMap bucketsByNameToManifestEntries(HashMap bucketsByName) {
		HashMap manifestEntries = new HashMap();
		Iterator i = bucketsByName.keySet().iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			Object o = bucketsByName.get(name);
			if(o instanceof Bucket) {
				Bucket data = (Bucket) bucketsByName.get(name);
				manifestEntries.put(name, new ManifestElement(name, data, null,data.size()));
			} else if(o instanceof HashMap) {
				manifestEntries.put(name, bucketsByNameToManifestEntries((HashMap)o));
			} else
				throw new IllegalArgumentException(String.valueOf(o));
		}
		return manifestEntries;
	}

	/**
	 * Convert a hierarchy of HashMap's of ManifestEntries into a series of 
	 * ManifestElement's, each of which has a full path.
	 */
	public static ManifestElement[] flatten(HashMap manifestElements) {
		Vector v = new Vector();
		flatten(manifestElements, v, "");
		return (ManifestElement[]) v.toArray(new ManifestElement[v.size()]);
	}

	public static void flatten(HashMap manifestElements, Vector v, String prefix) {
		Iterator i = manifestElements.keySet().iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			String fullName = prefix.length() == 0 ? name : prefix+ '/' +name;
			Object o = manifestElements.get(name);
			if(o instanceof HashMap) {
				flatten((HashMap)o, v, fullName);
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
	public static HashMap unflatten(Vector v) {
		HashMap manifestElements = new HashMap();
		for(int i=0;i<v.size();i++) {
			ManifestElement oldElement = (ManifestElement)v.get(i);
			add(oldElement, oldElement.getName(), manifestElements);
		}
		return manifestElements;
	}
	
	private static void add(ManifestElement e, String namePart, HashMap target) {
		int idx = namePart.indexOf('/');
		if(idx < 0) {
			target.put(namePart, new ManifestElement(e, namePart));
		} else {
			String before = namePart.substring(0, idx);
			String after = namePart.substring(idx+1);
			HashMap hm = (HashMap) (target.get(before));
			if(hm == null) {
				hm = new HashMap();
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
		if(persistent())
			container.activate(cb, 1);
		cb.onMajorProgress(container);
	}

	protected void onFetchable(PutHandler handler, ObjectContainer container) {
		if(persistent()) {
			container.activate(putHandlersWaitingForFetchable, 1);
			container.activate(metadataPuttersUnfetchable, 1);
			container.activate(cb, 1);
		}
		synchronized(this) {
			putHandlersWaitingForFetchable.remove(handler);
			if(fetchable) return;
			if(!putHandlersWaitingForFetchable.isEmpty()) return;
			if(!hasResolvedBase) return;
			if(!metadataPuttersUnfetchable.isEmpty()) return;
			fetchable = true;
		}
		if(persistent()) {
			container.store(putHandlersWaitingForMetadata);
			container.store(this);
		}
		cb.onFetchable(this, container);
	}

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		Metadata m = (Metadata) state.getToken();
		if(persistent()) {
			container.activate(m, 100);
			container.activate(metadataPuttersUnfetchable, 1);
			container.activate(putHandlersWaitingForFetchable, 1);
			container.activate(cb, 1);
		}
		synchronized(this) {
			metadataPuttersUnfetchable.remove(m);
			if(!metadataPuttersUnfetchable.isEmpty()) return;
			if(fetchable) return;
			if(!putHandlersWaitingForFetchable.isEmpty()) return;
			fetchable = true;
		}
		if(persistent()) {
			container.store(metadataPuttersUnfetchable);
			container.store(this);
		}
		cb.onFetchable(this, container);
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

}

