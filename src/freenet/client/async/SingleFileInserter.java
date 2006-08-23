package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;

import freenet.client.InsertBlock;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.events.FinishedCompressionEvent;
import freenet.client.events.StartedCompressionEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.SSKBlock;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;
import freenet.support.io.Bucket;
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
	
	final BaseClientPutter parent;
	final InsertBlock block;
	final InserterContext ctx;
	final boolean metadata;
	final PutCompletionCallback cb;
	final boolean getCHKOnly;
	final boolean insertAsArchiveManifest;
	/** If true, we are not the top level request, and should not
	 * update our parent to point to us as current put-stage. */
	private final boolean reportMetadataOnly;
	public final Object token;
	private final boolean freeData;

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
	 * @throws InserterException
	 */
	SingleFileInserter(BaseClientPutter parent, PutCompletionCallback cb, InsertBlock block, 
			boolean metadata, InserterContext ctx, boolean dontCompress, 
			boolean getCHKOnly, boolean reportMetadataOnly, Object token, boolean insertAsArchiveManifest, boolean freeData) throws InserterException {
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
	}
	
	public void start(SimpleFieldSet fs) throws InserterException {
		if(fs != null) {
			String type = fs.get("Type");
			if(type.equals("SplitHandler")) {
				// Try to reconstruct SplitHandler.
				// If we succeed, we bypass both compression and FEC encoding!
				try {
					SplitHandler sh = new SplitHandler();
					sh.start(fs, false);
					cb.onTransition(this, sh);
					sh.schedule();
					return;
				} catch (ResumeException e) {
					Logger.error(this, "Failed to restore: "+e, e);
				}
			}
		}
		if(block.getData().size() > COMPRESS_OFF_THREAD_LIMIT) {
			// Run off thread
			OffThreadCompressor otc = new OffThreadCompressor();
			Thread t = new Thread(otc, "Compressor for "+this);
			Logger.minor(this, "Compressing off-thread: "+t);
			t.setDaemon(true);
			t.start();
		} else {
			tryCompress();
		}
	}

	private class OffThreadCompressor implements Runnable {
		public void run() {
			try {
				tryCompress();
			} catch (InserterException e) {
				cb.onFailure(e, SingleFileInserter.this);
			}
		}
	}
	
	private void tryCompress() throws InserterException {
		// First, determine how small it needs to be
		Bucket origData = block.getData();
		Bucket data = origData;
		int blockSize;
		boolean dontCompress = ctx.dontCompress;
		
		long origSize = data.size();
		String type = block.desiredURI.getKeyType().toUpperCase();
		if(type.equals("SSK") || type.equals("KSK") || type.equals("USK")) {
			blockSize = SSKBlock.DATA_LENGTH;
		} else if(type.equals("CHK")) {
			blockSize = CHKBlock.DATA_LENGTH;
		} else {
			throw new InserterException(InserterException.INVALID_URI, "Unknown key type: "+type, null);
		}
		
		Compressor bestCodec = null;
		Bucket bestCompressedData = null;

		if((origSize > blockSize) && (!ctx.dontCompress) && (!dontCompress)) {
			// Try to compress the data.
			// Try each algorithm, starting with the fastest and weakest.
			// Stop when run out of algorithms, or the compressed data fits in a single block.
			int algos = Compressor.countCompressAlgorithms();
			try {
				for(int i=0;i<algos;i++) {
					// Only produce if we are compressing *the original data*
					if(parent == cb)
						ctx.eventProducer.produceEvent(new StartedCompressionEvent(i));
					Compressor comp = Compressor.getCompressionAlgorithmByDifficulty(i);
					Bucket result;
					result = comp.compress(origData, ctx.persistentBucketFactory, Long.MAX_VALUE);
					if(result.size() < blockSize) {
						bestCodec = comp;
						data = result;
						if(bestCompressedData != null)
							ctx.bf.freeBucket(bestCompressedData);
						bestCompressedData = data;
						break;
					}
					if((bestCompressedData != null) && (result.size() <  bestCompressedData.size())) {
						ctx.bf.freeBucket(bestCompressedData);
						bestCompressedData = result;
						data = result;
						bestCodec = comp;
					} else if((bestCompressedData == null) && (result.size() < data.size())) {
						bestCompressedData = result;
						bestCodec = comp;
						data = result;
					}
				}
			} catch (IOException e) {
				throw new InserterException(InserterException.BUCKET_ERROR, e, null);
			} catch (CompressionOutputSizeException e) {
				// Impossible
				throw new Error(e);
			}
		}
		
		if(parent == cb) {
			ctx.eventProducer.produceEvent(new FinishedCompressionEvent(bestCodec == null ? -1 : bestCodec.codecNumberForMetadata(), origSize, data.size()));
			Logger.minor(this, "Compressed "+origSize+" to "+data.size()+" on "+this);
		}
		
		// Compressed data
		
		// Insert it...
		short codecNumber = bestCodec == null ? -1 : bestCodec.codecNumberForMetadata();

		if(block.getData().size() > Integer.MAX_VALUE)
			throw new InserterException(InserterException.INTERNAL_ERROR, "2GB+ should not encode to one block!", null);

		boolean noMetadata = (block.clientMetadata == null) || block.clientMetadata.isTrivial();
		if(metadata && !noMetadata) {
			throw new InserterException(InserterException.INTERNAL_ERROR, "MIME type set for a metadata insert!", null);
		}
		if(noMetadata && !insertAsArchiveManifest) {
			if(data.size() < blockSize) {
				// Just insert it
				ClientPutState bi =
					createInserter(parent, data, codecNumber, block.desiredURI, ctx, cb, metadata, (int)block.getData().size(), -1, getCHKOnly, true);
				cb.onTransition(this, bi);
				bi.schedule();
				cb.onBlockSetFinished(this);
				return;
			}
		}
		if (data.size() < ClientCHKBlock.MAX_COMPRESSED_DATA_LENGTH) {
			// Insert single block, then insert pointer to it
			if(reportMetadataOnly) {
				SingleBlockInserter dataPutter = new SingleBlockInserter(parent, data, codecNumber, FreenetURI.EMPTY_CHK_URI, ctx, cb, metadata, (int)origSize, -1, getCHKOnly, true, true, token);
				Metadata meta = new Metadata(insertAsArchiveManifest ? Metadata.ZIP_MANIFEST : Metadata.SIMPLE_REDIRECT, dataPutter.getURI(), block.clientMetadata);
				cb.onMetadata(meta, this);
				cb.onTransition(this, dataPutter);
				dataPutter.schedule();
				cb.onBlockSetFinished(this);
			} else {
				MultiPutCompletionCallback mcb = 
					new MultiPutCompletionCallback(cb, parent, token);
				SingleBlockInserter dataPutter = new SingleBlockInserter(parent, data, codecNumber, FreenetURI.EMPTY_CHK_URI, ctx, mcb, metadata, (int)origSize, -1, getCHKOnly, true, false, token);
				Metadata meta = new Metadata(insertAsArchiveManifest ? Metadata.ZIP_MANIFEST : Metadata.SIMPLE_REDIRECT, dataPutter.getURI(), block.clientMetadata);
				Bucket metadataBucket;
				try {
					metadataBucket = BucketTools.makeImmutableBucket(ctx.bf, meta.writeToByteArray());
				} catch (IOException e) {
					Logger.error(this, "Caught "+e, e);
					throw new InserterException(InserterException.BUCKET_ERROR, e, null);
				} catch (MetadataUnresolvedException e) {
					// Impossible, we're not inserting a manifest.
					Logger.error(this, "Caught "+e, e);
					throw new InserterException(InserterException.INTERNAL_ERROR, "Got MetadataUnresolvedException in SingleFileInserter: "+e.toString(), null);
				}
				ClientPutState metaPutter = createInserter(parent, metadataBucket, (short) -1, block.desiredURI, ctx, mcb, true, (int)origSize, -1, getCHKOnly, true);
				mcb.addURIGenerator(metaPutter);
				mcb.add(dataPutter);
				cb.onTransition(this, mcb);
				Logger.minor(this, ""+mcb+" : data "+dataPutter+" meta "+metaPutter);
				mcb.arm();
				dataPutter.schedule();
				metaPutter.schedule();
				cb.onBlockSetFinished(this);
			}
			return;
		}
		// Otherwise the file is too big to fit into one block
		// We therefore must make a splitfile
		// Job of SplitHandler: when the splitinserter has the metadata,
		// insert it. Then when the splitinserter has finished, and the
		// metadata insert has finished too, tell the master callback.
		if(reportMetadataOnly) {
			SplitFileInserter sfi = new SplitFileInserter(parent, cb, data, bestCodec, block.clientMetadata, ctx, getCHKOnly, metadata, token, insertAsArchiveManifest, false);
			cb.onTransition(this, sfi);
			sfi.start();
		} else {
			SplitHandler sh = new SplitHandler();
			SplitFileInserter sfi = new SplitFileInserter(parent, sh, data, bestCodec, block.clientMetadata, ctx, getCHKOnly, metadata, token, insertAsArchiveManifest, false);
			sh.sfi = sfi;
			cb.onTransition(this, sh);
			sfi.start();
		}
	}
	
	private ClientPutState createInserter(BaseClientPutter parent, Bucket data, short compressionCodec, FreenetURI uri, 
			InserterContext ctx, PutCompletionCallback cb, boolean isMetadata, int sourceLength, int token, boolean getCHKOnly, 
			boolean addToParent) throws InserterException {
		if(uri.getKeyType().equals("USK")) {
			try {
				return new USKInserter(parent, data, compressionCodec, uri, ctx, cb, isMetadata, sourceLength, token, 
					getCHKOnly, addToParent, this.token);
			} catch (MalformedURLException e) {
				throw new InserterException(InserterException.INVALID_URI, e, null);
			}
		} else {
			return new SingleBlockInserter(parent, data, compressionCodec, uri, ctx, cb, isMetadata, sourceLength, token, 
				getCHKOnly, addToParent, false, this.token);
		}
	}

	/**
	 * When we get the metadata, start inserting it to our target key.
	 * When we have inserted both the metadata and the splitfile,
	 * call the master callback.
	 */
	class SplitHandler implements PutCompletionCallback, ClientPutState {

		ClientPutState sfi;
		ClientPutState metadataPutter;
		boolean finished;
		boolean splitInsertSuccess;
		boolean metaInsertSuccess;
		boolean splitInsertSetBlocks;
		boolean metaInsertSetBlocks;
		boolean metaInsertStarted;
		boolean metaFetchable;
		boolean dataFetchable;

		/**
		 * Create a SplitHandler from a stored progress SimpleFieldSet.
		 * @param forceMetadata If true, the insert is metadata, regardless of what the
		 * encompassing SplitFileInserter says (i.e. it's multi-level metadata).
		 * @throws ResumeException Thrown if the resume fails.
		 * @throws InserterException Thrown if some other error prevents the insert
		 * from starting.
		 */
		void start(SimpleFieldSet fs, boolean forceMetadata) throws ResumeException, InserterException {
			
			boolean meta = metadata || forceMetadata;
			
			// Don't include the booleans; wait for the callback.
			
			SimpleFieldSet sfiFS = fs.subset("SplitFileInserter");
			if(sfiFS == null)
				throw new ResumeException("No SplitFileInserter");
			ClientPutState newSFI, newMetaPutter = null;
			newSFI = new SplitFileInserter(parent, this, forceMetadata ? null : block.clientMetadata, ctx, getCHKOnly, meta, token, insertAsArchiveManifest, sfiFS);
			Logger.minor(this, "Starting "+newSFI+" for "+this);
			fs.removeSubset("SplitFileInserter");
			SimpleFieldSet metaFS = fs.subset("MetadataPutter");
			if(metaFS != null) {
				try {
					String type = metaFS.get("Type");
					if(type.equals("SplitFileInserter")) {
						// FIXME insertAsArchiveManifest ?!?!?!
						newMetaPutter = 
							new SplitFileInserter(parent, this, null, ctx, getCHKOnly, true, token, insertAsArchiveManifest, metaFS);
					} else if(type.equals("SplitHandler")) {
						newMetaPutter = new SplitHandler();
						((SplitHandler)newMetaPutter).start(metaFS, true);
					}
				} catch (ResumeException e) {
					newMetaPutter = null;
					Logger.error(this, "Caught "+e, e);
					// Will be reconstructed later
				}
			}
			Logger.minor(this, "Metadata putter "+metadataPutter+" for "+this);
			fs.removeSubset("MetadataPutter");
			synchronized(this) {
				sfi = newSFI;
				metadataPutter = newMetaPutter;
			}
		}

		public SplitHandler() {
			// Default constructor
		}

		public synchronized void onTransition(ClientPutState oldState, ClientPutState newState) {
			if(oldState == sfi)
				sfi = newState;
			if(oldState == metadataPutter)
				metadataPutter = newState;
		}
		
		public void onSuccess(ClientPutState state) {
			Logger.minor(this, "onSuccess("+state+") for "+this);
			boolean lateStart = false;
			synchronized(this) {
				if(finished) return;
				if(state == sfi) {
					Logger.minor(this, "Splitfile insert succeeded for "+this+" : "+state);
					splitInsertSuccess = true;
					if(!metaInsertSuccess && !metaInsertStarted) {
						Logger.error(this, "Splitfile insert succeeded but metadata not started, starting anyway... "+metadataPutter+" for "+this+" ( "+sfi+" )");
						metaInsertStarted = true;
						lateStart = true;
					} else {
						Logger.minor(this, "Metadata already started for "+this+" : success="+metaInsertSuccess+" started="+metaInsertStarted);
					}
				} else if(state == metadataPutter) {
					Logger.minor(this, "Metadata insert succeeded for "+this+" : "+state);
					metaInsertSuccess = true;
				} else {
					Logger.error(this, "Unknown: "+state+" for "+this, new Exception("debug"));
				}
				if(splitInsertSuccess && metaInsertSuccess) {
					Logger.minor(this, "Both succeeded for "+this);
					finished = true;
				}
			}
			if(lateStart)
				startMetadata();
			else if(finished)
				cb.onSuccess(this);
		}

		public void onFailure(InserterException e, ClientPutState state) {
			synchronized(this) {
				if(finished) return;
			}
			fail(e);
		}

		public void onMetadata(Metadata meta, ClientPutState state) {
			InserterException e = null;
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
					e = new InserterException(InserterException.INTERNAL_ERROR, "Did not expect to get metadata for metadata inserter", null);
				} else if(state != sfi) {
					Logger.error(this, "Got metadata from unknown state "+state);
					e = new InserterException(InserterException.INTERNAL_ERROR, "Did not expect to get metadata for metadata inserter", null);
				} else {
					// Already started metadata putter ? (in which case we've got the metadata twice)
					if(metadataPutter != null) return;
				}
			}
			if(reportMetadataOnly) {
				cb.onMetadata(meta, this);
				return;
			}
			if(e != null) {
				onFailure(e, state);
				return;
			}
			Bucket metadataBucket;
			try {
				metadataBucket = BucketTools.makeImmutableBucket(ctx.bf, meta.writeToByteArray());
			} catch (IOException e1) {
				InserterException ex = new InserterException(InserterException.BUCKET_ERROR, e1, null);
				fail(ex);
				return;
			} catch (MetadataUnresolvedException e1) {
				Logger.error(this, "Impossible: "+e, e);
				InserterException ex = new InserterException(InserterException.INTERNAL_ERROR, "MetadataUnresolvedException in SingleFileInserter.SplitHandler: "+e1, null);
				ex.initCause(e1);
				fail(ex);
				return;
			}
			InsertBlock newBlock = new InsertBlock(metadataBucket, null, block.desiredURI);
			try {
				synchronized(this) {
					metadataPutter = new SingleFileInserter(parent, this, newBlock, true, ctx, false, getCHKOnly, false, token, false, true);
					if(!dataFetchable) return;
				}
				Logger.minor(this, "Putting metadata on "+metadataPutter+" from "+sfi+" ("+((SplitFileInserter)sfi).getLength()+")");
			} catch (InserterException e1) {
				cb.onFailure(e1, this);
				return;
			}
			startMetadata();
		}

		private void fail(InserterException e) {
			Logger.minor(this, "Failing: "+e, e);
			ClientPutState oldSFI = null;
			ClientPutState oldMetadataPutter = null;
			synchronized(this) {
				if(finished) return;
				finished = true;
				oldSFI = sfi;
				oldMetadataPutter = metadataPutter;
			}
			if(oldSFI != null)
				oldSFI.cancel();
			if(oldMetadataPutter != null)
				oldMetadataPutter.cancel();
			finished = true;
			cb.onFailure(e, this);
		}

		public BaseClientPutter getParent() {
			return parent;
		}

		public void onEncode(BaseClientKey key, ClientPutState state) {
			synchronized(this) {
				if(state != metadataPutter) return;
			}
			cb.onEncode(key, this);
		}

		public void cancel() {
			ClientPutState oldSFI = null;
			ClientPutState oldMetadataPutter = null;
			synchronized(this) {
				oldSFI = sfi;
				oldMetadataPutter = metadataPutter;
			}
			if(oldSFI != null)
				oldSFI.cancel();
			if(oldMetadataPutter != null)
				oldMetadataPutter.cancel();
		}

		public void onBlockSetFinished(ClientPutState state) {
			synchronized(this) {
				if(state == sfi)
					splitInsertSetBlocks = true;
				else if (state == metadataPutter)
					metaInsertSetBlocks = true;
				if(!(splitInsertSetBlocks && metaInsertSetBlocks)) 
					return;
			}
			cb.onBlockSetFinished(this);
		}

		public void schedule() throws InserterException {
			sfi.schedule();
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
			SimpleFieldSet fs = new SimpleFieldSet();
			fs.put("Type", "SplitHandler");
			if(curSFI != null)
				fs.put("SplitFileInserter", curSFI.getProgressFieldset());
			if(curMetadataPutter != null)
				fs.put("MetadataPutter", metadataPutter.getProgressFieldset());
			return fs;
		}

		public void onFetchable(ClientPutState state) {
			
			boolean meta;
			
			synchronized(this) {
				meta = (state == metadataPutter);
				if(meta) {
					if(!metaInsertStarted) {
						Logger.error(this, "Metadata insert not started yet got onFetchable for it: "+state+" on "+this);
					}
					if(metaFetchable) return;
					metaFetchable = true;
				} else {
					if(state != sfi) {
						Logger.error(this, "onFetchable for unknown state "+state);
						return;
					}
					dataFetchable = true;
					if(metaInsertStarted) return;
					metaInsertStarted = true;
				}
			}
			
			if(meta)
				cb.onFetchable(this);
			else
				startMetadata();
		}
		
		private void startMetadata() {
			try {
				ClientPutState putter;
				synchronized(this) {
					if(metadataPutter == null) {
						Logger.minor(this, "Cannot start metadata yet: no metadataPutter");
						return;
					}
					putter = metadataPutter;
				}
				Logger.minor(this, "Starting metadata inserter: "+putter+" for "+this);
				putter.schedule();
				Logger.minor(this, "Started metadata inserter: "+putter+" for "+this);
			} catch (InserterException e1) {
				Logger.error(this, "Failing "+this+" : "+e1, e1);
				fail(e1);
				return;
			}
		}
		
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void cancel() {
	}

	public void schedule() throws InserterException {
		start(null);
	}

	public Object getToken() {
		return token;
	}

	public SimpleFieldSet getProgressFieldset() {
		return null;
	}
}
