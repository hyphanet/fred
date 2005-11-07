package freenet.client;

import java.io.IOException;

import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.NodeCHK;
import freenet.node.LowLevelPutException;
import freenet.support.Bucket;
import freenet.support.BucketTools;

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
		
		int compressionCodec = -1; // no compression

		int bestCompressionCodec = -1; // no compression
		Bucket bestCompressedData;
		
		if(data.size() > NodeCHK.BLOCK_SIZE && (!ctx.dontCompress)) {
			// Try to compress the data.
			// Try each algorithm, starting with the fastest and weakest.
			// Stop when run out of algorithms, or the compressed data fits in a single block.
			int algos = Metadata.countCompressAlgorithms();
			for(int i=0;i<algos;i++) {
				Compressor comp = Metadata.getCompressionAlgorithmByDifficulty(i);
				Bucket result = comp.compress(data, ctx.bf);
				if(result.size() < NodeCHK.BLOCK_SIZE) {
					compressionCodec = -1;
					data = result;
					if(bestCompressedData != null)
						ctx.bf.freeBucket(bestCompressedData);
					break;
				}
				if(bestCompressedData != null && result.size() <  bestCompressedData.size()) {
					ctx.bf.freeBucket(bestCompressedData);
					bestCompressedData = result;
					bestCompressionCodec = comp.codecNumberForMetadata();
				} else if(bestCompressedData == null && result.size() < data.size()) {
					bestCompressedData = result;
					bestCompressionCodec = comp.codecNumberForMetadata();
				}
			}
			if(compressionCodec == -1) {
				compressionCodec = bestCompressionCodec;
				if(compressionCodec != -1) {
					data = bestCompressedData;
				}
			}
		}
		
		if(data.size() <= NodeCHK.BLOCK_SIZE) {
			if(compressionCodec == -1) {
				chk = ClientCHKBlock.encode(BucketTools.toByteArray(data), metadata, true);
			}
		}
		
		if(data.size() <= NodeCHK.BLOCK_SIZE ||
				data.size() <= ClientCHKBlock.MAX_LENGTH_BEFORE_COMPRESSION) {
			try {
				chk = ClientCHKBlock.encode(BucketTools.toByteArray(data), metadata);
				return simplePutCHK(chk, block.clientMetadata);
			} catch (CHKEncodeException e) {
				// Too big! Encode to a splitfile, below.
			} catch (IOException e) {
				throw new InserterException(InserterException.BUCKET_ERROR);
			}
		}
		
		// Too big, encode to a splitfile
		SplitInserter splitInsert = new SplitInserter(data, block.clientMetadata);
		splitInsert.run();
	}

	/**
	 * Simple insert. Only complication is that it might have some client metadata.
	 * @param chk The data encoded into a single CHK.
	 * @param clientMetadata The client metadata. If this is non-trivial, we will have to
	 * create a redirect document just to put the metadata in.
	 * @return The URI of the resulting CHK.
	 */
	private FreenetURI simplePutCHK(ClientCHKBlock chk, ClientMetadata clientMetadata) {
		try {
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
