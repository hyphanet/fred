package freenet.client;

import java.io.IOException;

import freenet.client.events.SimpleBlockPutEvent;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.NodeCHK;
import freenet.node.LowLevelPutException;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.compress.Compressor;

/**
 * Class that does high-level inserts.
 */
public class FileInserter {

	InserterContext ctx;

	public FileInserter(InserterContext context) {
		this.ctx = context;
	}

	/**
	 * Do an insert.
	 * @param block The data to insert.
	 * @return The URI of the inserted data.
	 * @throws InserterException 
	 */
	public FreenetURI run(InsertBlock block, boolean metadata) throws InserterException {
		if(!block.desiredURI.toString(false).equals("CHK@"))
			throw new InserterException(InserterException.INVALID_URI);
		
		// Insert the content.
		// If we have reason to create a metadata document, include the client metadata.
		// Otherwise only create one (a redirect) with the client metadata, if there is any.
		
		// First, can it fit into a single block?
		
		Bucket data = block.data;
		ClientCHKBlock chk;

		Compressor bestCodec = null;
		Bucket bestCompressedData = null;
		
		if(data.size() > NodeCHK.BLOCK_SIZE && (!ctx.dontCompress)) {
			// Try to compress the data.
			// Try each algorithm, starting with the fastest and weakest.
			// Stop when run out of algorithms, or the compressed data fits in a single block.
			int algos = Compressor.countCompressAlgorithms();
			try {
				for(int i=0;i<algos;i++) {
					Compressor comp = Compressor.getCompressionAlgorithmByDifficulty(i);
					Bucket result;
					result = comp.compress(data, ctx.bf);
					if(result.size() < NodeCHK.BLOCK_SIZE) {
						bestCodec = comp;
						data = result;
						if(bestCompressedData != null)
							ctx.bf.freeBucket(bestCompressedData);
						break;
					}
					if(bestCompressedData != null && result.size() <  bestCompressedData.size()) {
						ctx.bf.freeBucket(bestCompressedData);
						bestCompressedData = result;
						bestCodec = comp;
					} else if(bestCompressedData == null && result.size() < data.size()) {
						bestCompressedData = result;
						bestCodec = comp;
					}
				}
			} catch (IOException e) {
				throw new InserterException(InserterException.BUCKET_ERROR, e);
			}
		}
		
		if(data.size() <= NodeCHK.BLOCK_SIZE) {
			byte[] array;
			try {
				array = BucketTools.toByteArray(data);
			} catch (IOException e) {
				throw new InserterException(InserterException.BUCKET_ERROR, e);
			}
			try {
				if(bestCodec == null) {
					chk = ClientCHKBlock.encode(array, metadata, true, (short)-1);
				} else {
					chk = ClientCHKBlock.encode(array, metadata, false, bestCodec.codecNumberForMetadata());
				}
			} catch (CHKEncodeException e) {
				Logger.error(this, "Unexpected error: "+e, e);
				throw new InserterException(InserterException.INTERNAL_ERROR);
			}
			return simplePutCHK(chk, block.clientMetadata);
		}
		
		// Too big, encode to a splitfile
		SplitInserter splitInsert = new SplitInserter(data, block.clientMetadata, bestCodec, ctx.splitfileAlgorithm, ctx, this, NodeCHK.BLOCK_SIZE);
		return splitInsert.run();
	}

	/**
	 * Simple insert. Only complication is that it might have some client metadata.
	 * @param chk The data encoded into a single CHK.
	 * @param clientMetadata The client metadata. If this is non-trivial, we will have to
	 * create a redirect document just to put the metadata in.
	 * @return The URI of the resulting CHK.
	 * @throws InserterException If there was an error inserting the block.
	 */
	private FreenetURI simplePutCHK(ClientCHKBlock chk, ClientMetadata clientMetadata) throws InserterException {
		try {
			ctx.eventProducer.produceEvent(new SimpleBlockPutEvent(chk.getClientKey()));
			ctx.client.putCHK(chk);
		} catch (LowLevelPutException e) {
			translateException(e);
		}
		
		if(clientMetadata == null || clientMetadata.isTrivial())
			// Don't need a redirect for the metadata
			return chk.getClientKey().getURI();
		else {
			// Do need a redirect for the metadata
			Metadata metadata = new Metadata(Metadata.SIMPLE_REDIRECT, chk.getClientKey().getURI(), clientMetadata);
			return putMetadataCHK(metadata);
		}
	}

	private void translateException(LowLevelPutException e) throws InserterException {
		switch(e.code) {
		case LowLevelPutException.INTERNAL_ERROR:
			throw new InserterException(InserterException.INTERNAL_ERROR);
		case LowLevelPutException.REJECTED_OVERLOAD:
			throw new InserterException(InserterException.REJECTED_OVERLOAD);
		case LowLevelPutException.ROUTE_NOT_FOUND:
			throw new InserterException(InserterException.ROUTE_NOT_FOUND);
		default:
			Logger.error(this, "Unknown LowLevelPutException code: "+e.code+" on "+this);
			throw new InserterException(InserterException.INTERNAL_ERROR);
		}
	}

	/** Put a metadata CHK 
	 * @throws InserterException If the insert fails.
	 */
	private FreenetURI putMetadataCHK(Metadata metadata) throws InserterException {
		byte[] data = metadata.writeToByteArray();
		Bucket bucket;
		try {
			bucket = BucketTools.makeImmutableBucket(ctx.bf, data);
		} catch (IOException e) {
			throw new InserterException(InserterException.BUCKET_ERROR);
		}
		InsertBlock block = new InsertBlock(bucket, null, FreenetURI.EMPTY_CHK_URI);
		return run(block, true);
	}
}
