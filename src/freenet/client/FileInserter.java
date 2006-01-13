package freenet.client;

import java.io.IOException;
import java.net.MalformedURLException;

import freenet.client.events.BlockInsertErrorEvent;
import freenet.client.events.SimpleBlockPutEvent;
import freenet.keys.CHKBlock;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientSSK;
import freenet.keys.ClientSSKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKEncodeException;
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
	public FreenetURI run(InsertBlock block, boolean metadata, boolean getCHKOnly, boolean noRetries) throws InserterException {
		if(block.data == null)
			throw new NullPointerException();
		if(block.desiredURI.getKeyType().equalsIgnoreCase("CHK")) {
			if(!block.desiredURI.toString(false).equalsIgnoreCase("CHK@"))
				throw new InserterException(InserterException.INVALID_URI, null);
		} else if(!block.desiredURI.getKeyType().equalsIgnoreCase("SSK")) {
			throw new InserterException(InserterException.INVALID_URI, null);
		}
		
		// Insert the content.
		// If we have reason to create a metadata document, include the client metadata.
		// Otherwise only create one (a redirect) with the client metadata, if there is any.
		
		// First, can it fit into a single block?
		
		Bucket origData = block.data;
		Bucket data = block.data;
		int blockSize;
		int maxSourceDataSize;
		boolean isSSK = false;
		boolean dontCompress = false;
		
		long origSize = data.size();
		if(block.desiredURI.getKeyType().equals("SSK")) {
			blockSize = SSKBlock.DATA_LENGTH;
			isSSK = true;
			maxSourceDataSize = ClientSSKBlock.MAX_DECOMPRESSED_DATA_LENGTH;
			if(origSize > maxSourceDataSize)
				dontCompress = true;
			// If too big to fit in an SSK, don't even try.
		} else if(block.desiredURI.getKeyType().equals("CHK")) {
			blockSize = CHKBlock.DATA_LENGTH;
			maxSourceDataSize = ClientCHKBlock.MAX_LENGTH_BEFORE_COMPRESSION;
		} else {
			throw new InserterException(InserterException.INVALID_URI);
		}
		
		ClientCHKBlock chk;
		
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
		
		InsertableClientSSK isk = null;
		
		if(isSSK && data.size() <= SSKBlock.DATA_LENGTH && block.clientMetadata.isTrivial()) {
			short codec;
			if(bestCodec == null) {
				codec = -1;
			} else {
				codec = bestCodec.codecNumberForMetadata();
			}
			try {
				isk = InsertableClientSSK.create(block.desiredURI);
			} catch (MalformedURLException e1) {
				throw new InserterException(InserterException.INVALID_URI, e1, null);
			}
			ClientSSKBlock ssk;
			try {
				ssk = isk.encode(data, metadata, true, codec, data.size(), ctx.random);
			} catch (SSKEncodeException e) {
				throw new InserterException(InserterException.INTERNAL_ERROR, e, isk.getURI());
			} catch (IOException e) {
				throw new InserterException(InserterException.INTERNAL_ERROR, e, isk.getURI());
			}
			return simplePutSSK(ssk, getCHKOnly, noRetries);
		}
		
		if(isSSK) {
			// Insert as CHK
			// Create metadata pointing to it (include the clientMetadata if there is any).
			FreenetURI uri = run(new InsertBlock(block.data, new ClientMetadata(), FreenetURI.EMPTY_CHK_URI), metadata, getCHKOnly, noRetries);
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, uri, block.clientMetadata);
			Bucket bucket;
			try {
				bucket = BucketTools.makeImmutableBucket(ctx.bf, m.writeToByteArray());
			} catch (IOException e) {
				throw new InserterException(InserterException.INTERNAL_ERROR, e, isk.getURI());
			}
			return run(new InsertBlock(bucket, new ClientMetadata(), block.desiredURI), metadata, getCHKOnly, noRetries);
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
			return simplePutCHK(chk, block.clientMetadata, getCHKOnly, noRetries);
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
	private FreenetURI simplePutCHK(ClientCHKBlock chk, ClientMetadata clientMetadata, boolean getCHKOnly, boolean noRetries) throws InserterException {
		LowLevelPutException le = null;
		int rnfs = 0;
		for(int i=0;i<=ctx.maxInsertRetries;i++) {
			try {
				if(!getCHKOnly)
					ctx.eventProducer.produceEvent(new SimpleBlockPutEvent(chk.getClientKey()));
				if(!getCHKOnly)
					ctx.client.putKey(chk, ctx.starterClient, ctx.cacheLocalRequests);
				break;
			} catch (LowLevelPutException e) {
				le = e;
				switch(le.code) {
				case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
				case LowLevelPutException.REJECTED_OVERLOAD:
					rnfs = 0;
				}
				if(noRetries)
					break;
				if(le.code == LowLevelPutException.ROUTE_NOT_FOUND && ctx.consecutiveRNFsCountAsSuccess > 0) {
					rnfs++;
					if(rnfs >= ctx.consecutiveRNFsCountAsSuccess) {
						le = null;
						break;
					}
				}
			}
		}
		
		FreenetURI uri;
		
		if(clientMetadata == null || clientMetadata.isTrivial())
			// Don't need a redirect for the metadata
			 uri = chk.getClientKey().getURI();
		else {
			// Do need a redirect for the metadata
			Metadata metadata = new Metadata(Metadata.SIMPLE_REDIRECT, chk.getClientKey().getURI(), clientMetadata);
			uri = putMetadataCHK(metadata, getCHKOnly, noRetries);
		}
		
		if(le != null)
			translateException(le, uri);
		
		return uri;
	}

	private FreenetURI simplePutSSK(ClientSSKBlock ssk, boolean getCHKOnly, boolean noRetries) throws InserterException {
		LowLevelPutException le = null;
		int rnfs = 0;
		for(int i=0;i<=ctx.maxInsertRetries;i++) {
			try {
				if(!getCHKOnly)
					ctx.eventProducer.produceEvent(new SimpleBlockPutEvent(ssk.getClientKey()));
				if(!getCHKOnly)
					ctx.client.putKey(ssk, ctx.starterClient, ctx.cacheLocalRequests);
				break;
			} catch (LowLevelPutException e) {
				le = e;
				switch(le.code) {
				case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
				case LowLevelPutException.REJECTED_OVERLOAD:
					rnfs = 0;
				}
				if(noRetries)
					break;
				if(le.code == LowLevelPutException.ROUTE_NOT_FOUND && ctx.consecutiveRNFsCountAsSuccess > 0) {
					rnfs++;
					if(rnfs >= ctx.consecutiveRNFsCountAsSuccess) {
						le = null;
						break;
					}
				}
				if(le.code == LowLevelPutException.COLLISION)
					break;
			}
		}
		
		FreenetURI uri = ssk.getClientKey().getURI();
		
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
		case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
			throw new InserterException(InserterException.ROUTE_REALLY_NOT_FOUND, uri);
		case LowLevelPutException.COLLISION:
			throw new InserterException(InserterException.COLLISION, uri);
		default:
			Logger.error(this, "Unknown LowLevelPutException code: "+e.code+" on "+this);
			throw new InserterException(InserterException.INTERNAL_ERROR, e, null);
		}
	}

	/** Put a metadata CHK 
	 * @throws InserterException If the insert fails.
	 */
	private FreenetURI putMetadataCHK(Metadata metadata, boolean getCHKOnly, boolean noRetries) throws InserterException {
		byte[] data = metadata.writeToByteArray();
		Bucket bucket;
		try {
			bucket = BucketTools.makeImmutableBucket(ctx.bf, data);
		} catch (IOException e) {
			throw new InserterException(InserterException.BUCKET_ERROR, null);
		}
		InsertBlock block = new InsertBlock(bucket, null, FreenetURI.EMPTY_CHK_URI);
		return run(block, true, getCHKOnly, noRetries);
	}
}
