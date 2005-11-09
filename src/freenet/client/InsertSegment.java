package freenet.client;

/**
 * Segment of a splitfile, for insertion purposes.
 */
public class InsertSegment {

	final short splitfileAlgorithm;
	final SplitfileBlock[] origDataBlocks;
	
	public InsertSegment(short splitfileAlgorithm, SplitfileBlock[] origDataBlocks) {
		this.splitfileAlgorithm = splitfileAlgorithm;
		this.origDataBlocks = origDataBlocks;
	}

}
