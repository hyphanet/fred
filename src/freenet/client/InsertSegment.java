package freenet.client;

import java.io.IOException;

import freenet.keys.FreenetURI;
import freenet.support.BucketFactory;

/**
 * Segment of a splitfile, for insertion purposes.
 */
public class InsertSegment {

	final FECCodec codec;
	final SplitfileBlock[] origDataBlocks;
	final int blockLength;
	final BucketFactory bf;
	/** Check blocks. Will be created by encode(...). */
	final SplitfileBlock[] checkBlocks;
	final boolean getCHKOnly;
	
	public InsertSegment(short splitfileAlgo, SplitfileBlock[] origDataBlocks, int blockLength, BucketFactory bf, boolean getCHKOnly) {
		this.origDataBlocks = origDataBlocks;
		codec = FECCodec.getCodec(splitfileAlgo, origDataBlocks.length);
		checkBlocks = new SplitfileBlock[codec.countCheckBlocks()];
		this.blockLength = blockLength;
		this.bf = bf;
		this.getCHKOnly = getCHKOnly;
	}

	/**
	 * Get the check block URIs.
	 * Don't call before encode()! Don't call before all blocks have inserted either.
	 */
	public FreenetURI[] getCheckURIs() {
		FreenetURI[] uris = new FreenetURI[checkBlocks.length];
		for(int i=0;i<uris.length;i++) {
			FreenetURI uri = checkBlocks[i].getURI();
			uris[i] = uri;
		}
		return uris;
	}

	/**
	 * Encode the data blocks into check blocks.
	 * @return The number of check blocks generated.
	 * @throws IOException If the encode fails due to a bucket error.
	 */
	public int encode(int offset, RetryTracker tracker, InserterContext ctx) throws IOException {
		if(codec == null) return 0; // no FEC
		for(int i=0;i<checkBlocks.length;i++)
			checkBlocks[i] = new BlockInserter(null, offset + i, tracker, ctx, getCHKOnly);
		codec.encode(origDataBlocks, checkBlocks, blockLength, bf);
		for(int i=0;i<checkBlocks.length;i++)
			tracker.addBlock(checkBlocks[i]);
		return checkBlocks.length;
	}

}
