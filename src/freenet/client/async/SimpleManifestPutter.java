package freenet.client.async;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.InsertBlock;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;

public class SimpleManifestPutter extends BaseClientPutter implements PutCompletionCallback {
	// Only implements PutCompletionCallback for the final metadata insert

	private class PutHandler extends BaseClientPutter implements PutCompletionCallback {

		protected PutHandler(String name, Bucket data, ClientMetadata cm, boolean getCHKOnly) throws InserterException {
			super(SimpleManifestPutter.this.getPriorityClass(), SimpleManifestPutter.this.chkScheduler, SimpleManifestPutter.this.sskScheduler, SimpleManifestPutter.this.client);
			this.name = name;
			this.cm = cm;
			InsertBlock block = 
				new InsertBlock(data, cm, FreenetURI.EMPTY_CHK_URI);
			this.origSFI =
				new SingleFileInserter(this, this, block, false, ctx, false, getCHKOnly, false);
			currentState = origSFI;
			metadata = null;
		}

		private SingleFileInserter origSFI;
		private ClientPutState currentState;
		private ClientMetadata cm;
		private final String name;
		private byte[] metadata;
		private boolean finished;
		
		public void start() throws InserterException {
			origSFI.start();
			origSFI = null;
		}
		
		public FreenetURI getURI() {
			return null;
		}

		public boolean isFinished() {
			return finished || cancelled || SimpleManifestPutter.this.cancelled;
		}

		public void onSuccess(ClientPutState state) {
			Logger.minor(this, "Completed "+this);
			synchronized(SimpleManifestPutter.this) {
				runningPutHandlers.remove(this);
				if(!runningPutHandlers.isEmpty()) {
					return;
				}
			}
			insertedAllFiles();
		}

		public void onFailure(InserterException e, ClientPutState state) {
			Logger.minor(this, "Failed: "+this+" - "+e, e);
			fail(e);
		}

		public void onEncode(ClientKey key, ClientPutState state) {
			if(metadata == null) {
				// Don't have metadata yet
				// Do have key
				// So make a redirect to the key
				Metadata m =
					new Metadata(Metadata.SIMPLE_REDIRECT, key.getURI(), cm);
				onMetadata(m, null);
			}
		}

		public void onTransition(ClientPutState oldState, ClientPutState newState) {
			if(oldState == this) {
				// We do not need to have a hashtable of state -> PutHandler.
				// Because we can just pull the parent off the state!
				this.currentState = newState;
			}
		}

		public void onMetadata(Metadata m, ClientPutState state) {
			if(metadata != null) {
				Logger.error(this, "Reassigning metadata", new Exception("debug"));
				return;
			}
			metadata = m.writeToByteArray();
			synchronized(SimpleManifestPutter.this) {
				putHandlersWaitingForMetadata.remove(this);
				if(!putHandlersWaitingForMetadata.isEmpty()) return;
				gotAllMetadata();
			}
		}

		public void addBlock() {
			SimpleManifestPutter.this.addBlock();
		}
		
		public void addBlocks(int num) {
			SimpleManifestPutter.this.addBlocks(num);
		}
		
		public void completedBlock(boolean dontNotify) {
			SimpleManifestPutter.this.completedBlock(dontNotify);
		}
		
		public void failedBlock() {
			SimpleManifestPutter.this.failedBlock();
		}
		
		public void fatallyFailedBlock() {
			SimpleManifestPutter.this.fatallyFailedBlock();
		}
		
		public void addMustSucceedBlocks(int blocks) {
			SimpleManifestPutter.this.addMustSucceedBlocks(blocks);
		}
		
		public void notifyClients() {
			// FIXME generate per-filename events???
		}

		public void onBlockSetFinished(ClientPutState state) {
			synchronized(SimpleManifestPutter.this) {
				waitingForBlockSets.remove(this);
				if(!waitingForBlockSets.isEmpty()) return;
			}
			SimpleManifestPutter.this.blockSetFinalized();
		}
	}

	private final HashMap putHandlersByName;
	private final HashSet runningPutHandlers;
	private final HashSet putHandlersWaitingForMetadata;
	private final HashSet waitingForBlockSets;
	private FreenetURI finalURI;
	private FreenetURI targetURI;
	private boolean finished;
	private final InserterContext ctx;
	private final ClientCallback cb;
	private final boolean getCHKOnly;
	private boolean insertedAllFiles;
	private boolean insertedManifest;
	private ClientPutState currentMetadataInserterState;
	private final String defaultName;
	private final static String[] defaultDefaultNames =
		new String[] { "index.html", "index.htm", "default.html", "default.htm" };
	
	public SimpleManifestPutter(ClientCallback cb, ClientRequestScheduler chkSched,
			ClientRequestScheduler sskSched, HashSet manifestElements, short prioClass, FreenetURI target, 
			String defaultName, InserterContext ctx, boolean getCHKOnly, Object clientContext) throws InserterException {
		super(prioClass, chkSched, sskSched, clientContext);
		this.defaultName = defaultName;
		this.targetURI = target;
		this.cb = cb;
		this.ctx = ctx;
		this.getCHKOnly = getCHKOnly;
		putHandlersByName = new HashMap();
		runningPutHandlers = new HashSet();
		putHandlersWaitingForMetadata = new HashSet();
		waitingForBlockSets = new HashSet();
		Iterator it = manifestElements.iterator();
		while(it.hasNext()) {
			ManifestElement element = (ManifestElement) it.next();
			String name = element.name;
			Bucket data = element.data;
			String mimeType = element.mimeOverride;
			if(mimeType == null)
				mimeType = DefaultMIMETypes.guessMIMEType(name);
			ClientMetadata cm;
			if(mimeType.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE))
				cm = null;
			else
				cm = new ClientMetadata(mimeType);
			PutHandler ph;
			try {
				ph = new PutHandler(name, data, cm, getCHKOnly);
			} catch (InserterException e) {
				cancelAndFinish();
				throw e;
			}
			putHandlersByName.put(name, ph);
			runningPutHandlers.add(ph);
			putHandlersWaitingForMetadata.add(ph);
		}
		it = putHandlersByName.values().iterator();
		while(it.hasNext()) {
			PutHandler ph = (PutHandler) it.next();
			try {
				ph.start();
			} catch (InserterException e) {
				cancelAndFinish();
				throw e;
			}
		}		
	}
	
	public FreenetURI getURI() {
		return finalURI;
	}

	public boolean isFinished() {
		return finished || cancelled;
	}

	private void gotAllMetadata() {
		Logger.minor(this, "Got all metadata");
		HashMap namesToByteArrays = new HashMap();
		Iterator i = putHandlersByName.values().iterator();
		while(i.hasNext()) {
			PutHandler ph = (PutHandler) i.next();
			String name = ph.name;
			byte[] meta = ph.metadata;
			namesToByteArrays.put(name, meta);
		}
		if(defaultName != null) {
			byte[] meta = (byte[]) namesToByteArrays.get(defaultName);
			if(meta == null) {
				fail(new InserterException(InserterException.INVALID_URI, "Default name "+defaultName+" does not exist", null));
				return;
			}
			namesToByteArrays.put("", meta);
		} else {
			for(int j=0;j<defaultDefaultNames.length;j++) {
				String name = defaultDefaultNames[j];
				byte[] meta = (byte[]) namesToByteArrays.get(name);
				if(meta != null) {
					namesToByteArrays.put("", meta);
					break;
				}
			}
		}
		Metadata meta =
			Metadata.mkRedirectionManifestWithMetadata(namesToByteArrays);
		Bucket bucket;
		try {
			bucket = BucketTools.makeImmutableBucket(ctx.bf, meta.writeToByteArray());
		} catch (IOException e) {
			fail(new InserterException(InserterException.BUCKET_ERROR, e, null));
			return;
		}
		InsertBlock block =
			new InsertBlock(bucket, null, targetURI);
		try {
			SingleFileInserter metadataInserter = 
				new SingleFileInserter(this, this, block, true, ctx, false, getCHKOnly, false);
			this.currentMetadataInserterState = metadataInserter;
			metadataInserter.start();
		} catch (InserterException e) {
			fail(e);
		}
	}
	
	private void insertedAllFiles() {
		synchronized(this) {
			insertedAllFiles = true;
			if(finished || cancelled) return;
			if(!insertedManifest) return;
			finished = true;
		}
		complete();
	}
	
	private void complete() {
		cb.onSuccess(this);
	}

	private void fail(InserterException e) {
		// Cancel all, then call the callback
		cancelAndFinish();
		
		cb.onFailure(e, this);
	}

	private void cancelAndFinish() {
		PutHandler[] running;
		synchronized(this) {
			if(finished) return;
			running = (PutHandler[]) runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
			finished = true;
		}
		
		for(int i=0;i<running.length;i++) {
			running[i].cancel();
		}
	}
	
	public void onSuccess(ClientPutState state) {
		synchronized(this) {
			insertedManifest = true;
			if(!finished) return;
			if(!insertedAllFiles) return;
			finished = true;
		}
		complete();
	}
	
	public void onFailure(InserterException e, ClientPutState state) {
		// FIXME check state == currentMetadataInserterState ??
		fail(e);
	}
	
	public void onEncode(ClientKey key, ClientPutState state) {
		this.finalURI = key.getURI();
		Logger.minor(this, "Got metadata key: "+finalURI);
		cb.onGeneratedURI(finalURI, this);
	}
	
	public void onTransition(ClientPutState oldState, ClientPutState newState) {
		if(oldState == currentMetadataInserterState)
			currentMetadataInserterState = newState;
		else
			Logger.error(this, "Current state = "+currentMetadataInserterState+" called onTransition(old="+oldState+", new="+newState+")", 
					new Exception("debug"));
	}
	
	public void onMetadata(Metadata m, ClientPutState state) {
		Logger.error(this, "Got metadata from "+state+" on "+this+" (metadata inserter = "+currentMetadataInserterState);
		fail(new InserterException(InserterException.INTERNAL_ERROR));
	}

	public void notifyClients() {
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.blockSetFinalized));
	}

	public void onBlockSetFinished(ClientPutState state) {
		this.blockSetFinalized();
	}

	/**
	 * Convert a HashMap of name -> bucket to a HashSet of ManifestEntry's.
	 * All are to have mimeOverride=null, i.e. we use the auto-detected mime type
	 * from the filename.
	 */
	public static HashSet bucketsByNameToManifestEntries(HashMap bucketsByName) {
		HashSet manifestEntries = new HashSet();
		Iterator i = bucketsByName.keySet().iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			Bucket data = (Bucket) bucketsByName.get(name);
			manifestEntries.add(new ManifestElement(name, data, null));
		}
		return manifestEntries;
	}

}
