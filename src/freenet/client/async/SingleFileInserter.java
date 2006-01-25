package freenet.client.async;

import java.io.IOException;

import freenet.client.InsertBlock;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.SSKBlock;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;

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
	
	final ClientPutter parent;
	final InsertBlock block;
	final InserterContext ctx;
	final boolean metadata;
	final PutCompletionCallback cb;
	final boolean getCHKOnly;
	/** If true, we are not the top level request, and should not
	 * update our parent to point to us as current put-stage. */
	final boolean dontTellParent;
	private boolean cancelled = false;

	SingleFileInserter(ClientPutter parent, PutCompletionCallback cb, InsertBlock block, 
			boolean metadata, InserterContext ctx, boolean dontCompress, 
			boolean dontTellParent, boolean getCHKOnly) throws InserterException {
		this.parent = parent;
		this.block = block;
		this.ctx = ctx;
		this.metadata = metadata;
		this.cb = cb;
		this.dontTellParent = dontTellParent;
		this.getCHKOnly = getCHKOnly;
		if(!dontTellParent)
			parent.setCurrentState(this);
	}
	
	public void start() throws InserterException {
		if((!ctx.dontCompress) && block.getData().size() > COMPRESS_OFF_THREAD_LIMIT) {
			// Run off thread
			OffThreadCompressor otc = new OffThreadCompressor();
			Thread t = new Thread(otc, "Compressor for "+this);
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
		if(type.equals("SSK") || type.equals("KSK")) {
			blockSize = SSKBlock.DATA_LENGTH;
		} else if(type.equals("CHK")) {
			blockSize = CHKBlock.DATA_LENGTH;
		} else {
			throw new InserterException(InserterException.INVALID_URI);
		}
		
		Compressor bestCodec = null;
		Bucket bestCompressedData = null;

		if(origSize > blockSize && (!ctx.dontCompress) && (!dontCompress)) {
			// Try to compress the data.
			// Try each algorithm, starting with the fastest and weakest.
			// Stop when run out of algorithms, or the compressed data fits in a single block.
			int algos = Compressor.countCompressAlgorithms();
			try {
				for(int i=0;i<algos;i++) {
					Compressor comp = Compressor.getCompressionAlgorithmByDifficulty(i);
					Bucket result;
					result = comp.compress(origData, ctx.bf, Long.MAX_VALUE);
					if(result.size() < blockSize) {
						bestCodec = comp;
						data = result;
						if(bestCompressedData != null)
							ctx.bf.freeBucket(bestCompressedData);
						bestCompressedData = data;
						break;
					}
					if(bestCompressedData != null && result.size() <  bestCompressedData.size()) {
						ctx.bf.freeBucket(bestCompressedData);
						bestCompressedData = result;
						data = result;
						bestCodec = comp;
					} else if(bestCompressedData == null && result.size() < data.size()) {
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
		
		// Compressed data
		
		// Insert it...
		short codecNumber = bestCodec == null ? -1 : bestCodec.codecNumberForMetadata();

		if(block.getData().size() > Integer.MAX_VALUE)
			throw new InserterException(InserterException.INTERNAL_ERROR, "2GB+ should not encode to one block!", null);
		
		if((block.clientMetadata == null || block.clientMetadata.isTrivial())) {
			if(data.size() < blockSize) {
				// Just insert it
				SingleBlockInserter bi = new SingleBlockInserter(parent, data, codecNumber, block.desiredURI, ctx, cb, metadata, (int)block.getData().size(), -1, getCHKOnly);
				bi.schedule();
				cb.onTransition(this, bi);
				return;
			}
		}
		if (data.size() < ClientCHKBlock.MAX_COMPRESSED_DATA_LENGTH) {
			MultiPutCompletionCallback mcb = 
				new MultiPutCompletionCallback(cb, parent, dontTellParent);
			// Insert single block, then insert pointer to it
			SingleBlockInserter dataPutter = new SingleBlockInserter(parent, data, codecNumber, FreenetURI.EMPTY_CHK_URI, ctx, mcb, metadata, (int)origSize, -1, getCHKOnly);
			Metadata meta = new Metadata(Metadata.SIMPLE_REDIRECT, dataPutter.getURI(), block.clientMetadata);
			Bucket metadataBucket;
			try {
				metadataBucket = BucketTools.makeImmutableBucket(ctx.bf, meta.writeToByteArray());
			} catch (IOException e) {
				throw new InserterException(InserterException.BUCKET_ERROR, e, null);
			}
			SingleBlockInserter metaPutter = new SingleBlockInserter(parent, metadataBucket, (short) -1, block.desiredURI, ctx, mcb, true, (int)origSize, -1, getCHKOnly);
			mcb.addURIGenerator(metaPutter);
			mcb.add(dataPutter);
			cb.onTransition(this, mcb);
			mcb.arm();
			dataPutter.schedule();
			metaPutter.schedule();
			return;
		}
		// Otherwise the file is too big to fit into one block
		// We therefore must make a splitfile
		// Job of SplitHandler: when the splitinserter has the metadata,
		// insert it. Then when the splitinserter has finished, and the
		// metadata insert has finished too, tell the master callback.
		SplitHandler sh = new SplitHandler();
		SplitFileInserter sfi = new SplitFileInserter(parent, sh, data, bestCodec, block.clientMetadata, ctx, sh, getCHKOnly, metadata, true);
		sh.sfi = sfi;
		if(!dontTellParent)
			parent.setCurrentState(sh);
		cb.onTransition(this, sh);
		sfi.start();
		return;
	}
	
	/**
	 * When we get the metadata, start inserting it to our target key.
	 * When we have inserted both the metadata and the splitfile,
	 * call the master callback.
	 */
	class SplitHandler implements SplitPutCompletionCallback, ClientPutState {

		ClientPutState sfi;
		ClientPutState metadataPutter;
		boolean finished = false;
		boolean splitInsertSuccess = false;
		boolean metaInsertSuccess = false;

		public synchronized void onTransition(ClientPutState oldState, ClientPutState newState) {
			if(oldState == sfi)
				sfi = newState;
			if(oldState == metadataPutter)
				metadataPutter = newState;
		}
		
		public void onSuccess(ClientPutState state) {
			Logger.minor(this, "onSuccess("+state+")");
			synchronized(this) {
				if(finished) return;
				if(state == sfi) {
					Logger.minor(this, "Splitfile insert succeeded");
					splitInsertSuccess = true;
				} else if(state == metadataPutter) {
					Logger.minor(this, "Metadata insert succeeded");
					metaInsertSuccess = true;
				} else {
					Logger.error(this, "Unknown: "+state);
				}
				if(splitInsertSuccess && metaInsertSuccess) {
					Logger.minor(this, "Both succeeded");
					finished = true;
				}
				else return;
			}
			cb.onSuccess(this);
		}

		public synchronized void onFailure(InserterException e, ClientPutState state) {
			if(finished) return;
			fail(e);
		}

		public void onGeneratedMetadata(Metadata meta) {
			if(finished) return;
			synchronized(this) {
				Bucket metadataBucket;
				try {
					metadataBucket = BucketTools.makeImmutableBucket(ctx.bf, meta.writeToByteArray());
				} catch (IOException e) {
					InserterException ex = new InserterException(InserterException.BUCKET_ERROR, e, null);
					fail(ex);
					return;
				}
				InsertBlock newBlock = new InsertBlock(metadataBucket, null, block.desiredURI);
				try {
					metadataPutter = new SingleFileInserter(parent, this, newBlock, true, ctx, false, getCHKOnly, false);
					Logger.minor(this, "Putting metadata on "+metadataPutter);
				} catch (InserterException e) {
					cb.onFailure(e, this);
					return;
				}
			}
			try {
				((SingleFileInserter)metadataPutter).start();
			} catch (InserterException e) {
				fail(e);
				return;
			}
		}

		private synchronized void fail(InserterException e) {
			Logger.minor(this, "Failing: "+e, e);
			if(finished) return;
			finished = true;
			cb.onFailure(e, this);
		}

		public ClientPutter getParent() {
			return parent;
		}

		public void onEncode(ClientKey key, ClientPutState state) {
			if(state == metadataPutter)
				cb.onEncode(key, this);
		}

		public void cancel() {
			if(sfi != null)
				sfi.cancel();
			if(metadataPutter != null)
				metadataPutter.cancel();
		}
		
	}

	public ClientPutter getParent() {
		return parent;
	}

	public void cancel() {
		cancelled = true;
	}
}
