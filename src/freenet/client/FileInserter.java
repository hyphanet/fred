package freenet.client;

import java.io.IOException;

import freenet.client.events.GeneratedURIEvent;
import freenet.client.events.SimpleBlockPutEvent;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.NodeCHK;
import freenet.node.LowLevelPutException;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.compress.CompressionOutputSizeException;
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
	 * @param localOnly 
	 * @return The URI of the inserted data.
	 * @throws InserterException 
	 */
	public FreenetURI run(InsertBlock block, boolean metadata, boolean getCHKOnly) throws InserterException {
		if(block.data == null)
			throw new NullPointerException();
		if(!block.desiredURI.toString(false).equals("CHK@"))
			throw new InserterException(InserterException.INVALID_URI, null);
		
		// Insert the content.
		// If we have reason to create a metadata document, include the client metadata.
		// Otherwise only create one (a redirect) with the client metadata, if there is any.
		
		// First, can it fit into a single block?
		
		Bucket data = block.data;
		ClientCHKBlock chk;

		Compressor bestCodec = null;
		Bucket bestCompressedData = null;

		long origSize = data.size();
		if(data.size() > NodeCHK.BLOCK_SIZE && (!ctx.dontCompress)) {
			// Try to compress the data.
			// Try each algorithm, starting with the fastest and weakest.
			// Stop when run out of algorithms, or the compressed data fits in a single block.
			int algos = Compressor.countCompressAlgorithms();
			try {
				for(int i=0;i<algos;i++) {
					Compressor comp = Compressor.getCompressionAlgorithmByDifficulty(i);
					Bucket result;
					result = comp.compress(data, ctx.bf, Long.MAX_VALUE);
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
				throw new InserterException(InserterException.BUCKET_ERROR, e, null);
			} catch (CompressionOutputSizeException e) {
				// Impossible
				throw new Error(e);
			}
		}
		
		if(data.size() <= NodeCHK.BLOCK_SIZE) {
			try {
				if(bestCodec == null) {
					chk = ClientCHKBlock.encode(data, metadata, true, (short)-1, 0);
				} else {
					if(origSize > ClientCHKBlock.MAX_LENGTH_BEFORE_COMPRESSION)
						throw new IllegalArgumentException("Data too big to compress into single block, but it does");
					chk = ClientCHKBlock.encode(data, metadata, false, bestCodec.codecNumberForMetadata(), (int)origSize);
				}
			} catch (IOException e) {
				throw new InserterException(InserterException.BUCKET_ERROR, e, null);
			} catch (CHKEncodeException e) {
				Logger.error(this, "Unexpected error: "+e, e);
				throw new InserterException(InserterException.INTERNAL_ERROR, null);
			}
			return simplePutCHK(chk, block.clientMetadata, getCHKOnly);
		}
		
		// Too big, encode to a splitfile
		SplitInserter splitInsert = new SplitInserter(data, block.clientMetadata, bestCodec, ctx.splitfileAlgorithm, ctx, this, NodeCHK.BLOCK_SIZE, getCHKOnly, metadata);
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
	private FreenetURI simplePutCHK(ClientCHKBlock chk, ClientMetadata clientMetadata, boolean getCHKOnly) throws InserterException {
		LowLevelPutException le = null;
		try {
			ctx.eventProducer.produceEvent(new SimpleBlockPutEvent(chk.getClientKey()));
			if(!getCHKOnly)
				ctx.client.putCHK(chk, ctx.starterClient, ctx.cacheLocalRequests);
		} catch (LowLevelPutException e) {
			le = e;
		}
		
		FreenetURI uri;
		
		if(clientMetadata == null || clientMetadata.isTrivial())
			// Don't need a redirect for the metadata
			 uri = chk.getClientKey().getURI();
		else {
			// Do need a redirect for the metadata
			Metadata metadata = new Metadata(Metadata.SIMPLE_REDIRECT, chk.getClientKey().getURI(), clientMetadata);
			uri = putMetadataCHK(metadata, getCHKOnly);
		}
		
		if(le != null)
			translateException(le, uri);
		
		return uri;
	}

	private void translateException(LowLevelPutException e, FreenetURI uri) throws InserterException {
		switch(e.code) {
		case LowLevelPutException.INTERNAL_ERROR:
			throw new InserterException(InserterException.INTERNAL_ERROR, e, null);
		case LowLevelPutException.REJECTED_OVERLOAD:
			throw new InserterException(InserterException.REJECTED_OVERLOAD, uri);
		case LowLevelPutException.ROUTE_NOT_FOUND:
			throw new InserterException(InserterException.ROUTE_NOT_FOUND, uri);
		default:
			Logger.error(this, "Unknown LowLevelPutException code: "+e.code+" on "+this);
			throw new InserterException(InserterException.INTERNAL_ERROR, e, null);
		}
	}

	/** Put a metadata CHK 
	 * @throws InserterException If the insert fails.
	 */
	private FreenetURI putMetadataCHK(Metadata metadata, boolean getCHKOnly) throws InserterException {
		byte[] data = metadata.writeToByteArray();
		Bucket bucket;
		try {
			bucket = BucketTools.makeImmutableBucket(ctx.bf, data);
		} catch (IOException e) {
			throw new InserterException(InserterException.BUCKET_ERROR, null);
		}
		InsertBlock block = new InsertBlock(bucket, null, FreenetURI.EMPTY_CHK_URI);
		return run(block, true, getCHKOnly);
	}
}
