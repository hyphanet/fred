package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;

import com.db4o.ObjectContainer;

import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.events.FinishedCompressionEvent;
import freenet.client.events.StartedCompressionEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.CHKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.SSKBlock;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor;
import freenet.support.io.BucketChainBucketFactory;
import freenet.support.io.BucketTools;

/**
 * Attempt to insert a file. May include metadata.
 * 
 * This stage:
 * Attempt to compress the file. Off-thread if it will take a while.
 * Then hand it off to SimpleFileInserter.
 */
class SingleFileInserter implements ClientPutState {

	// Config option???
	private static final long COMPRESS_OFF_THREAD_LIMIT = 65536;
	
	private static boolean logMINOR;
	final BaseClientPutter parent;
	final InsertBlock block;
	final InsertContext ctx;
	final boolean metadata;
	final PutCompletionCallback cb;
	final boolean getCHKOnly;
	final boolean insertAsArchiveManifest;
	/** If true, we are not the top level request, and should not
	 * update our parent to point to us as current put-stage. */
	private final boolean reportMetadataOnly;
	public final Object token;
	private final boolean freeData; // this is being set, but never read ???
	private final String targetFilename;
	private final boolean earlyEncode;
	private final boolean persistent;
	private boolean started;
	private boolean cancelled;
	
	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.
	
	private final int hashCode;
	
	public int hashCode() {
		return hashCode;
	}

	/**
	 * @param parent
	 * @param cb
	 * @param block
	 * @param metadata
	 * @param ctx
	 * @param dontCompress
	 * @param getCHKOnly
	 * @param reportMetadataOnly If true, don't insert the metadata, just report it.
	 * @param insertAsArchiveManifest If true, insert the metadata as an archive manifest.
	 * @param freeData If true, free the data when possible.
	 * @param targetFilename 
	 * @param earlyEncode If true, try to get a URI as quickly as possible.
	 * @throws InsertException
	 */
	SingleFileInserter(BaseClientPutter parent, PutCompletionCallback cb, InsertBlock block, 
			boolean metadata, InsertContext ctx, boolean dontCompress, 
			boolean getCHKOnly, boolean reportMetadataOnly, Object token, boolean insertAsArchiveManifest, 
			boolean freeData, String targetFilename, boolean earlyEncode) {
		hashCode = super.hashCode();
		this.earlyEncode = earlyEncode;
		this.reportMetadataOnly = reportMetadataOnly;
		this.token = token;
		this.parent = parent;
		this.block = block;
		this.ctx = ctx;
		this.metadata = metadata;
		this.cb = cb;
		this.getCHKOnly = getCHKOnly;
		this.insertAsArchiveManifest = insertAsArchiveManifest;
		this.freeData = freeData;
		this.targetFilename = targetFilename;
		this.persistent = parent.persistent();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	public void start(SimpleFieldSet fs, ObjectContainer container, ClientContext context) throws InsertException {
		if(fs != null) {
			String type = fs.get("Type");
			if(type.equals("SplitHandler")) {
				// Try to reconstruct SplitHandler.
				// If we succeed, we bypass both compression and FEC encoding!
				try {
					SplitHandler sh = new SplitHandler();
					sh.start(fs, false, container, context);
					boolean wasActive = true;
					
					if(persistent) {
						wasActive = container.ext().isActive(cb);
						if(!wasActive)
							container.activate(cb, 1);
					}
					cb.onTransition(this, sh, container);
					sh.schedule(container, context);
					if(!wasActive)
						container.deactivate(cb, 1);
					return;
				} catch (ResumeException e) {
					Logger.error(this, "Failed to restore: "+e, e);
				}
			}
		}
		if(persistent) {
			container.activate(block, 1); // will cascade
		}
		tryCompress(container, context);
	}

	void onCompressed(CompressionOutput output, ObjectContainer container, ClientContext context) {
		boolean cbActive = true;
		if(persistent) {
			cbActive = container.ext().isActive(cb);
			if(!cbActive)
				container.activate(cb, 1);
		}
		if(started) {
			Logger.error(this, "Already started, not starting again", new Exception("error"));
			return;
		}
		try {
			onCompressedInner(output, container, context);
		} catch (InsertException e) {
			cb.onFailure(e, SingleFileInserter.this, container, context);
        } catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
			System.err.println("OffThreadCompressor thread above failed.");
			// Might not be heap, so try anyway
			cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR, e, null), SingleFileInserter.this, container, context);
        } catch (Throwable t) {
            Logger.error(this, "Caught in OffThreadCompressor: "+t, t);
            System.err.println("Caught in OffThreadCompressor: "+t);
            t.printStackTrace();
            // Try to fail gracefully
			cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR, t, null), SingleFileInserter.this, container, context);
		}
		if(!cbActive)
			container.deactivate(cb, 1);
	}
	
	void onCompressedInner(CompressionOutput output, ObjectContainer container, ClientContext context) throws InsertException {
		boolean parentWasActive = true;
		if(container != null) {
			container.activate(block, 2);
			parentWasActive = container.ext().isActive(parent);
			if(!parentWasActive)
				container.activate(parent, 1);
		}
		long origSize = block.getData().size();
		Bucket bestCompressedData = output.data;
		Bucket data = bestCompressedData;
		Compressor bestCodec = output.bestCodec;
		
		boolean freeData = false;
		if(bestCodec != null) {
			freeData = true;
		} else {
			data = block.getData();
		}

		int blockSize;
		int oneBlockCompressedSize;
		
		String type = block.desiredURI.getKeyType();
		if(type.equals("SSK") || type.equals("KSK") || type.equals("USK")) {
			blockSize = SSKBlock.DATA_LENGTH;
			oneBlockCompressedSize = SSKBlock.MAX_COMPRESSED_DATA_LENGTH;
		} else if(type.equals("CHK")) {
			blockSize = CHKBlock.DATA_LENGTH;
			oneBlockCompressedSize = CHKBlock.MAX_COMPRESSED_DATA_LENGTH;
		} else {
			throw new InsertException(InsertException.INVALID_URI, "Unknown key type: "+type, null);
		}
		
		// Compressed data
		
		if(parent == cb) {
			if(persistent) {
				container.activate(ctx, 1);
				container.activate(ctx.eventProducer, 1);
			}
			ctx.eventProducer.produceEvent(new FinishedCompressionEvent(bestCodec == null ? -1 : bestCodec.codecNumberForMetadata(), origSize, data.size()), container, context);
			if(logMINOR) Logger.minor(this, "Compressed "+origSize+" to "+data.size()+" on "+this);
		}
		
		// Insert it...
		short codecNumber = bestCodec == null ? -1 : bestCodec.codecNumberForMetadata();
		long compressedDataSize = data.size();
		boolean fitsInOneBlockAsIs = bestCodec == null ? compressedDataSize < blockSize : compressedDataSize < oneBlockCompressedSize;
		boolean fitsInOneCHK = bestCodec == null ? compressedDataSize < CHKBlock.DATA_LENGTH : compressedDataSize < CHKBlock.MAX_COMPRESSED_DATA_LENGTH;

		if((fitsInOneBlockAsIs || fitsInOneCHK) && block.getData().size() > Integer.MAX_VALUE)
			throw new InsertException(InsertException.INTERNAL_ERROR, "2GB+ should not encode to one block!", null);

		boolean noMetadata = ((block.clientMetadata == null) || block.clientMetadata.isTrivial()) && targetFilename == null;
		if(noMetadata && !insertAsArchiveManifest) {
			if(fitsInOneBlockAsIs) {
				// Just insert it
				ClientPutState bi =
					createInserter(parent, data, codecNumber, block.desiredURI, ctx, cb, metadata, (int)block.getData().size(), -1, getCHKOnly, true, true, container, context);
				if(logMINOR)
					Logger.minor(this, "Inserting without metadata: "+bi+" for "+this);
				cb.onTransition(this, bi, container);
				bi.schedule(container, context);
				cb.onBlockSetFinished(this, container, context);
				started = true;
				if(persistent) {
					container.store(this);
					if(!parentWasActive)
						container.deactivate(parent, 1);
				}
				return;
			}
		}
		if (fitsInOneCHK) {
			// Insert single block, then insert pointer to it
			if(reportMetadataOnly) {
				SingleBlockInserter dataPutter = new SingleBlockInserter(parent, data, codecNumber, FreenetURI.EMPTY_CHK_URI, ctx, cb, metadata, (int)origSize, -1, getCHKOnly, true, true, token, container, context, persistent);
				if(logMINOR)
					Logger.minor(this, "Inserting with metadata: "+dataPutter+" for "+this);
				Metadata meta = makeMetadata(dataPutter.getURI(container, context));
				cb.onMetadata(meta, this, container, context);
				cb.onTransition(this, dataPutter, container);
				dataPutter.schedule(container, context);
				cb.onBlockSetFinished(this, container, context);
			} else {
				MultiPutCompletionCallback mcb = 
					new MultiPutCompletionCallback(cb, parent, token);
				SingleBlockInserter dataPutter = new SingleBlockInserter(parent, data, codecNumber, FreenetURI.EMPTY_CHK_URI, ctx, mcb, metadata, (int)origSize, -1, getCHKOnly, true, false, token, container, context, persistent);
				if(logMINOR)
					Logger.minor(this, "Inserting data: "+dataPutter+" for "+this);
				Metadata meta = makeMetadata(dataPutter.getURI(container, context));
				Bucket metadataBucket;
				try {
					metadataBucket = BucketTools.makeImmutableBucket(context.getBucketFactory(persistent), meta.writeToByteArray());
				} catch (IOException e) {
					Logger.error(this, "Caught "+e, e);
					throw new InsertException(InsertException.BUCKET_ERROR, e, null);
				} catch (MetadataUnresolvedException e) {
					// Impossible, we're not inserting a manifest.
					Logger.error(this, "Caught "+e, e);
					throw new InsertException(InsertException.INTERNAL_ERROR, "Got MetadataUnresolvedException in SingleFileInserter: "+e.toString(), null);
				}
				ClientPutState metaPutter = createInserter(parent, metadataBucket, (short) -1, block.desiredURI, ctx, mcb, true, (int)origSize, -1, getCHKOnly, true, false, container, context);
				if(logMINOR)
					Logger.minor(this, "Inserting metadata: "+metaPutter+" for "+this);
				mcb.addURIGenerator(metaPutter, container);
				mcb.add(dataPutter, container);
				cb.onTransition(this, mcb, container);
				Logger.minor(this, ""+mcb+" : data "+dataPutter+" meta "+metaPutter);
				mcb.arm(container, context);
				dataPutter.schedule(container, context);
				if(metaPutter instanceof SingleBlockInserter)
					((SingleBlockInserter)metaPutter).encode(container, context, true);
				metaPutter.schedule(container, context);
				cb.onBlockSetFinished(this, container, context);
			}
			started = true;
			if(persistent) {
				container.store(this);
				if(!parentWasActive)
					container.deactivate(parent, 1);
			}
			return;
		}
		// Otherwise the file is too big to fit into one block
		// We therefore must make a splitfile
		// Job of SplitHandler: when the splitinserter has the metadata,
		// insert it. Then when the splitinserter has finished, and the
		// metadata insert has finished too, tell the master callback.
		if(reportMetadataOnly) {
			SplitFileInserter sfi = new SplitFileInserter(parent, cb, data, bestCodec, origSize, block.clientMetadata, ctx, getCHKOnly, metadata, token, insertAsArchiveManifest, freeData, persistent, container, context);
			if(logMINOR)
				Logger.minor(this, "Inserting as splitfile: "+sfi+" for "+this);
			cb.onTransition(this, sfi, container);
			sfi.start(container, context);
			if(earlyEncode) sfi.forceEncode(container, context);
			if(persistent) {
				container.store(sfi);
				container.deactivate(sfi, 1);
			}
		} else {
			SplitHandler sh = new SplitHandler();
			SplitFileInserter sfi = new SplitFileInserter(parent, sh, data, bestCodec, origSize, block.clientMetadata, ctx, getCHKOnly, metadata, token, insertAsArchiveManifest, freeData, persistent, container, context);
			sh.sfi = sfi;
			if(logMINOR)
				Logger.minor(this, "Inserting as splitfile: "+sfi+" for "+sh+" for "+this);
			if(persistent)
				container.store(sh);
			cb.onTransition(this, sh, container);
			sfi.start(container, context);
			if(earlyEncode) sfi.forceEncode(container, context);
			if(persistent) {
				container.store(sfi);
				container.deactivate(sfi, 1);
			}
		}
		started = true;
		if(persistent) {
			container.store(this);
			if(!parentWasActive)
				container.deactivate(parent, 1);
		}
	}
	
	private void tryCompress(ObjectContainer container, ClientContext context) throws InsertException {
		// First, determine how small it needs to be
		Bucket origData = block.getData();
		Bucket data = origData;
		int blockSize;
		int oneBlockCompressedSize;
		boolean dontCompress = ctx.dontCompress;
		
		long origSize = data.size();
		if(persistent)
			container.activate(block.desiredURI, 5);
		String type = block.desiredURI.getKeyType();
		if(type.equals("SSK") || type.equals("KSK") || type.equals("USK")) {
			blockSize = SSKBlock.DATA_LENGTH;
			oneBlockCompressedSize = SSKBlock.MAX_COMPRESSED_DATA_LENGTH;
		} else if(type.equals("CHK")) {
			blockSize = CHKBlock.DATA_LENGTH;
			oneBlockCompressedSize = CHKBlock.MAX_COMPRESSED_DATA_LENGTH;
		} else {
			throw new InsertException(InsertException.INVALID_URI, "Unknown key type: "+type, null);
		}
		
		boolean tryCompress = (origSize > blockSize) && (!ctx.dontCompress) && (!dontCompress);
		if(tryCompress) {
			InsertCompressor.start(container, context, this, origData, oneBlockCompressedSize, context.getBucketFactory(persistent), persistent);
		} else {
			CompressionOutput output = new CompressionOutput(data, null);
			onCompressed(output, container, context);
		}
	}
	
	private Metadata makeMetadata(FreenetURI uri) {
		Metadata meta = new Metadata(insertAsArchiveManifest ? Metadata.ZIP_MANIFEST : Metadata.SIMPLE_REDIRECT, uri, block.clientMetadata);
		if(targetFilename != null) {
			HashMap hm = new HashMap();
			hm.put(targetFilename, meta);
			meta = Metadata.mkRedirectionManifestWithMetadata(hm);
		}
		return meta;
	}

	private ClientPutState createInserter(BaseClientPutter parent, Bucket data, short compressionCodec, FreenetURI uri, 
			InsertContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, boolean getCHKOnly, 
			boolean addToParent, boolean encodeCHK, ObjectContainer container, ClientContext context) throws InsertException {
		
		uri.checkInsertURI(); // will throw an exception if needed
		
		if(uri.getKeyType().equals("USK")) {
			try {
				return new USKInserter(parent, data, compressionCodec, uri, ctx, cb, isMetadata, sourceLength, token, 
					getCHKOnly, addToParent, this.token, container, context);
			} catch (MalformedURLException e) {
				throw new InsertException(InsertException.INVALID_URI, e, null);
			}
		} else {
			SingleBlockInserter sbi = 
				new SingleBlockInserter(parent, data, compressionCodec, uri, ctx, cb, isMetadata, sourceLength, token, 
						getCHKOnly, addToParent, false, this.token, container, context, persistent);
			if(encodeCHK)
				cb.onEncode(sbi.getBlock(container, context, true).getClientKey(), this, container, context);
			return sbi;
		}
		
	}
	
	/**
	 * When we get the metadata, start inserting it to our target key.
	 * When we have inserted both the metadata and the splitfile,
	 * call the master callback.
	 * 
	 * This class has to be public so that db4o can access objectOnActivation
	 * through reflection.
	 */
	public class SplitHandler implements PutCompletionCallback, ClientPutState {

		ClientPutState sfi;
		ClientPutState metadataPutter;
		boolean finished;
		boolean splitInsertSuccess;
		boolean metaInsertSuccess;
		boolean splitInsertSetBlocks;
		boolean metaInsertSetBlocks;
		boolean metaInsertStarted;
		boolean metaFetchable;
		final boolean persistent;

		/**
		 * Create a SplitHandler from a stored progress SimpleFieldSet.
		 * @param forceMetadata If true, the insert is metadata, regardless of what the
		 * encompassing SplitFileInserter says (i.e. it's multi-level metadata).
		 * @throws ResumeException Thrown if the resume fails.
		 * @throws InsertException Thrown if some other error prevents the insert
		 * from starting.
		 */
		void start(SimpleFieldSet fs, boolean forceMetadata, ObjectContainer container, ClientContext context) throws ResumeException, InsertException {
			
			boolean parentWasActive = true;
			if(persistent) {
				parentWasActive = container.ext().isActive(parent);
				if(!parentWasActive)
					container.activate(parent, 1);
			}
			
			boolean meta = metadata || forceMetadata;
			
			// Don't include the booleans; wait for the callback.
			
			SimpleFieldSet sfiFS = fs.subset("SplitFileInserter");
			if(sfiFS == null)
				throw new ResumeException("No SplitFileInserter");
			ClientPutState newSFI, newMetaPutter = null;
			newSFI = new SplitFileInserter(parent, this, forceMetadata ? null : block.clientMetadata, ctx, getCHKOnly, meta, token, insertAsArchiveManifest, sfiFS, container, context);
			if(logMINOR) Logger.minor(this, "Starting "+newSFI+" for "+this);
			fs.removeSubset("SplitFileInserter");
			SimpleFieldSet metaFS = fs.subset("MetadataPutter");
			if(metaFS != null) {
				try {
					String type = metaFS.get("Type");
					if(type.equals("SplitFileInserter")) {
						// FIXME insertAsArchiveManifest ?!?!?!
						newMetaPutter = 
							new SplitFileInserter(parent, this, null, ctx, getCHKOnly, true, token, insertAsArchiveManifest, metaFS, container, context);
					} else if(type.equals("SplitHandler")) {
						newMetaPutter = new SplitHandler();
						((SplitHandler)newMetaPutter).start(metaFS, true, container, context);
					}
				} catch (ResumeException e) {
					newMetaPutter = null;
					Logger.error(this, "Caught "+e, e);
					// Will be reconstructed later
				}
			}
			if(logMINOR) Logger.minor(this, "Metadata putter "+metadataPutter+" for "+this);
			fs.removeSubset("MetadataPutter");
			synchronized(this) {
				sfi = newSFI;
				metadataPutter = newMetaPutter;
			}
			if(persistent) {
				container.store(this);
				if(!parentWasActive)
					container.deactivate(parent, 1);
			}
		}

		public SplitHandler() {
			// Default constructor
			this.persistent = SingleFileInserter.this.persistent;
		}

		public synchronized void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
			if(persistent) { // FIXME debug-point
				if(logMINOR) Logger.minor(this, "Transition: "+oldState+" -> "+newState);
			}
			if(oldState == sfi)
				sfi = newState;
			if(oldState == metadataPutter)
				metadataPutter = newState;
			if(persistent)
				container.store(this);
		}
		
		public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(block, 2);
			}
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "onSuccess("+state+") for "+this);
			boolean lateStart = false;
			synchronized(this) {
				if(finished){
					if(freeData) {
						block.free(container);
						if(persistent)
							container.store(this);
					}
					return;
				}
				if(state == sfi) {
					if(logMINOR) Logger.minor(this, "Splitfile insert succeeded for "+this+" : "+state);
					splitInsertSuccess = true;
					if(!metaInsertSuccess && !metaInsertStarted) {
						lateStart = true;
					} else {
						if(logMINOR) Logger.minor(this, "Metadata already started for "+this+" : success="+metaInsertSuccess+" started="+metaInsertStarted);
					}
				} else if(state == metadataPutter) {
					if(logMINOR) Logger.minor(this, "Metadata insert succeeded for "+this+" : "+state);
					metaInsertSuccess = true;
				} else {
					Logger.error(this, "Unknown: "+state+" for "+this, new Exception("debug"));
				}
				if(splitInsertSuccess && metaInsertSuccess) {
					if(logMINOR) Logger.minor(this, "Both succeeded for "+this);
					finished = true;
				}
			}
			if(persistent)
				container.store(this);
			if(lateStart)
				startMetadata(container, context);
			else if(finished) {
				if(persistent)
					container.activate(cb, 1);
				cb.onSuccess(this, container, context);
				if(persistent)
					container.deactivate(cb, 1);
			}
		}

		public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(block, 1);
			}
			synchronized(this) {
				if(finished){
					if(freeData)
						block.free(container);
					return;
				}
			}
			fail(e, container, context);
		}

		public void onMetadata(Metadata meta, ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) {
				container.activate(cb, 1);
				container.activate(block, 2);
			}
			InsertException e = null;
			synchronized(this) {
				if(finished) return;
				if(reportMetadataOnly) {
					if(state != sfi) {
						Logger.error(this, "Got metadata from unknown object "+state+" when expecting to report metadata");
						return;
					}
					metaInsertSuccess = true;
				} else if(state == metadataPutter) {
					Logger.error(this, "Got metadata for metadata");
					e = new InsertException(InsertException.INTERNAL_ERROR, "Did not expect to get metadata for metadata inserter", null);
				} else if(state != sfi) {
					Logger.error(this, "Got metadata from unknown state "+state+" sfi="+sfi+" metadataPutter="+metadataPutter+" on "+this+" persistent="+persistent, new Exception("debug"));
					e = new InsertException(InsertException.INTERNAL_ERROR, "Got metadata from unknown state", null);
				} else {
					// Already started metadata putter ? (in which case we've got the metadata twice)
					if(metadataPutter != null) return;
				}
			}
			if(reportMetadataOnly) {
				if(persistent)
					container.store(this);
				cb.onMetadata(meta, this, container, context);
				return;
			}
			if(e != null) {
				onFailure(e, state, container, context);
				return;
			}
			
			byte[] metaBytes;
			if(persistent)
				// Load keys
				container.activate(meta, 100);
			try {
				metaBytes = meta.writeToByteArray();
			} catch (MetadataUnresolvedException e1) {
				Logger.error(this, "Impossible: "+e1, e1);
				InsertException ex = new InsertException(InsertException.INTERNAL_ERROR, "MetadataUnresolvedException in SingleFileInserter.SplitHandler: "+e1, null);
				ex.initCause(e1);
				fail(ex, container, context);
				return;
			}
			
			String metaPutterTargetFilename = targetFilename;
			
			if(targetFilename != null) {
				
				if(metaBytes.length <= Short.MAX_VALUE) {
					HashMap hm = new HashMap();
					hm.put(targetFilename, meta);
					meta = Metadata.mkRedirectionManifestWithMetadata(hm);
					metaPutterTargetFilename = null;
					try {
						metaBytes = meta.writeToByteArray();
					} catch (MetadataUnresolvedException e1) {
						Logger.error(this, "Impossible (2): "+e1, e1);
						InsertException ex = new InsertException(InsertException.INTERNAL_ERROR, "MetadataUnresolvedException in SingleFileInserter.SplitHandler(2): "+e1, null);
						ex.initCause(e1);
						fail(ex, container, context);
						return;
					}
				}
			}
			
			Bucket metadataBucket;
			try {
				metadataBucket = BucketTools.makeImmutableBucket(context.getBucketFactory(persistent), metaBytes);
			} catch (IOException e1) {
				InsertException ex = new InsertException(InsertException.BUCKET_ERROR, e1, null);
				fail(ex, container, context);
				return;
			}
			InsertBlock newBlock = new InsertBlock(metadataBucket, null, block.desiredURI);
				synchronized(this) {
					metadataPutter = new SingleFileInserter(parent, this, newBlock, true, ctx, false, getCHKOnly, false, token, false, true, metaPutterTargetFilename, earlyEncode);
					// If EarlyEncode, then start the metadata insert ASAP, to get the key.
					// Otherwise, wait until the data is fetchable (to improve persistence).
					if(logMINOR)
						Logger.minor(this, "Created metadata putter for "+this+" : "+metadataPutter+" bucket "+metadataBucket+" size "+metadataBucket.size());
					if(persistent)
						container.store(this);
					if(!(earlyEncode || splitInsertSuccess)) return;
				}
				if(logMINOR) Logger.minor(this, "Putting metadata on "+metadataPutter+" from "+sfi+" ("+((SplitFileInserter)sfi).getLength()+ ')');
			startMetadata(container, context);
		}

		private void fail(InsertException e, ObjectContainer container, ClientContext context) {
			if(logMINOR) Logger.minor(this, "Failing: "+e, e);
			ClientPutState oldSFI = null;
			ClientPutState oldMetadataPutter = null;
			if(persistent)
				container.activate(block, 2);
			synchronized(this) {
				if(finished){
					if(freeData)
						block.free(container);
					return;
				}
				finished = true;
				oldSFI = sfi;
				oldMetadataPutter = metadataPutter;
			}
			if(persistent) {
				if(oldSFI != null)
					container.activate(oldSFI, 1);
				if(oldMetadataPutter != null)
					container.activate(oldMetadataPutter, 1);
				container.activate(cb, 1);
			}
			if(oldSFI != null)
				oldSFI.cancel(container, context);
			if(oldMetadataPutter != null)
				oldMetadataPutter.cancel(container, context);
			finished = true;
			cb.onFailure(e, this, container, context);
			if(persistent)
				container.store(this);
		}

		public BaseClientPutter getParent() {
			return parent;
		}

		public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
			if(persistent) // FIXME debug-point
				if(logMINOR) Logger.minor(this, "onEncode() for "+this+" : "+state+" : "+key);
			synchronized(this) {
				if(state != metadataPutter) return;
			}
			cb.onEncode(key, this, container, context);
		}

		public void cancel(ObjectContainer container, ClientContext context) {
			if(persistent) // FIXME debug-point
				if(logMINOR) Logger.minor(this, "Cancelling "+this);
			ClientPutState oldSFI = null;
			ClientPutState oldMetadataPutter = null;
			synchronized(this) {
				oldSFI = sfi;
				oldMetadataPutter = metadataPutter;
			}
			if(persistent) {
				container.store(this);
				if(oldSFI != null)
					container.activate(oldSFI, 1);
				if(oldMetadataPutter != null)
					container.activate(oldMetadataPutter, 1);
			}
			if(oldSFI != null)
				oldSFI.cancel(container, context);
			if(oldMetadataPutter != null)
				oldMetadataPutter.cancel(container, context);
			
			if(freeData) {
				if(persistent)
					container.activate(block, 2);
				block.free(container);
			}
		}

		public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
			synchronized(this) {
				if(state == sfi)
					splitInsertSetBlocks = true;
				else if (state == metadataPutter)
					metaInsertSetBlocks = true;
				if(persistent)
					container.store(this);
				if(!(splitInsertSetBlocks && metaInsertSetBlocks)) 
					return;
			}
			if(persistent)
				container.activate(cb, 1);
			cb.onBlockSetFinished(this, container, context);
		}

		public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
			if(persistent)
				container.activate(sfi, 1);
			sfi.schedule(container, context);
		}

		public Object getToken() {
			return token;
		}

		public SimpleFieldSet getProgressFieldset() {
			ClientPutState curSFI;
			ClientPutState curMetadataPutter;
			synchronized(this) {
				curSFI = sfi;
				curMetadataPutter = metadataPutter;
			}
			SimpleFieldSet fs = new SimpleFieldSet(false);
			fs.putSingle("Type", "SplitHandler");
			if(curSFI != null)
				fs.put("SplitFileInserter", curSFI.getProgressFieldset());
			if(curMetadataPutter != null)
				fs.put("MetadataPutter", metadataPutter.getProgressFieldset());
			return fs;
		}

		public void onFetchable(ClientPutState state, ObjectContainer container) {

			if(persistent) // FIXME debug-point
				if(logMINOR) Logger.minor(this, "onFetchable on "+this);
			
			logMINOR = Logger.shouldLog(Logger.MINOR, this);

			if(logMINOR) Logger.minor(this, "onFetchable("+state+ ')');
			
			boolean meta;
			
			synchronized(this) {
				meta = (state == metadataPutter);
				if(meta) {
					if(!metaInsertStarted) {
						Logger.error(this, "Metadata insert not started yet got onFetchable for it: "+state+" on "+this);
					}
					if(logMINOR) Logger.minor(this, "Metadata fetchable"+(metaFetchable?"":" already"));
					if(metaFetchable) return;
					metaFetchable = true;
					if(persistent)
						container.store(this);
				} else {
					if(state != sfi) {
						Logger.error(this, "onFetchable for unknown state "+state);
						return;
					}
					if(persistent)
						container.store(this);
					if(logMINOR) Logger.minor(this, "Data fetchable");
					if(metaInsertStarted) return;
				}
			}
			
			if(meta) {
				if(persistent)
					container.activate(cb, 1);
				cb.onFetchable(this, container);
			}
		}
		
		private void startMetadata(ObjectContainer container, ClientContext context) {
			if(persistent) // FIXME debug-point
				if(logMINOR) Logger.minor(this, "startMetadata() on "+this);
			try {
				ClientPutState putter;
				ClientPutState splitInserter;
				synchronized(this) {
					if(metaInsertStarted) return;
					if(persistent && metadataPutter != null)
						container.activate(metadataPutter, 1);
					putter = metadataPutter;
					if(putter == null) {
						if(logMINOR) Logger.minor(this, "Cannot start metadata yet: no metadataPutter");
					} else
						metaInsertStarted = true;
					splitInserter = sfi;
				}
				if(persistent)
					container.store(this);
				if(putter != null) {
					if(logMINOR) Logger.minor(this, "Starting metadata inserter: "+putter+" for "+this);
					putter.schedule(container, context);
					if(logMINOR) Logger.minor(this, "Started metadata inserter: "+putter+" for "+this);
				} else {
					// Get all the URIs ASAP so we can start to insert the metadata.
					if(persistent)
						container.activate(splitInserter, 1);
					((SplitFileInserter)splitInserter).forceEncode(container, context);
				}
			} catch (InsertException e1) {
				Logger.error(this, "Failing "+this+" : "+e1, e1);
				fail(e1, container, context);
				return;
			}
		}
		
		public void objectOnActivate(ObjectContainer container) {
			// Chain to containing class, since we use its members extensively.
			container.activate(SingleFileInserter.this, 1);
		}
		
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		cancelled = true;
		if(freeData)
			block.free(container);
		if(persistent)
			container.store(this);
	}

	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		start(null, container, context);
	}

	public Object getToken() {
		return token;
	}

	public SimpleFieldSet getProgressFieldset() {
		return null;
	}

	public void onStartCompression(final int i, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(ctx, 2);
		}
		if(parent == cb) {
			if(ctx == null) throw new NullPointerException();
			if(ctx.eventProducer == null) throw new NullPointerException();
			ctx.eventProducer.produceEvent(new StartedCompressionEvent(i), container, context);
		}
	}
	
	boolean cancelled() {
		return cancelled;
	}
	
	boolean started() {
		return started;
	}
}
