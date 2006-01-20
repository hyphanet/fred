package freenet.client;

import java.io.IOException;

import freenet.keys.FreenetURI;
import freenet.support.BucketFactory;
import freenet.support.Logger;

/**
 * Segment of a splitfile, for insertion purposes.
 */
public class InsertSegment {

	final FECCodec codec;
	final StartableSplitfileBlock[] origDataBlocks;
	final int blockLength;
	final BucketFactory bf;
	/** Check blocks. Will be created by encode(...). */
	final StartableSplitfileBlock[] checkBlocks;
	final boolean getCHKOnly;
	// just for debugging
	final int segNo;
	
	public InsertSegment(short splitfileAlgo, StartableSplitfileBlock[] origDataBlocks, int blockLength, BucketFactory bf, boolean getCHKOnly, int segNo) {
		this.origDataBlocks = origDataBlocks;
		codec = FECCodec.getCodec(splitfileAlgo, origDataBlocks.length);
		if(codec != null)
			checkBlocks = new StartableSplitfileBlock[codec.countCheckBlocks()];
		else
			checkBlocks = new StartableSplitfileBlock[0];
		this.blockLength = blockLength;
		this.bf = bf;
		this.getCHKOnly = getCHKOnly;
		this.segNo = segNo;
		// FIXME: remove debugging code
		for(int i=0;i<origDataBlocks.length;i++)
			if(origDataBlocks[i].getData() == null) throw new NullPointerException("Block "+i+" of "+origDataBlocks.length+" data blocks of seg "+segNo+" is null");
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
		Logger.minor(this, "Encoding "+segNo+": "+origDataBlocks.length+" into "+checkBlocks.length);
		if(codec == null) return 0; // no FEC
		for(int i=0;i<checkBlocks.length;i++)
			checkBlocks[i] = new BlockInserter(null, offset + i, tracker, ctx, getCHKOnly);
		codec.encode(origDataBlocks, checkBlocks, blockLength, bf);
		for(int i=0;i<checkBlocks.length;i++)
			tracker.addBlock(checkBlocks[i]);
		return checkBlocks.length;
	}

}
