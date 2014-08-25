package freenet.client.async;

import java.util.Random;

import freenet.node.KeysFetchingLocally;

/** Tracks retry count and completion status for blocks in an insert segment. */
public class SplitFileInserterSegmentBlockChooser extends SimpleBlockChooser {
    
    final SplitFileInserterSegmentStorage segment;
    final KeysFetchingLocally keysFetching;

    public SplitFileInserterSegmentBlockChooser(SplitFileInserterSegmentStorage segment, 
            int blocks, Random random, int maxRetries, KeysFetchingLocally keysFetching) {
        super(blocks, random, maxRetries);
        this.segment = segment;
        this.keysFetching = keysFetching;
    }
    
    protected int getMaxBlockNumber() {
        // Ignore cross-segment: We either send all blocks, if the segment has been encoded, or
        // only the data blocks, if it hasn't (even if the cross-segment blocks have been encoded).
        if(segment.hasEncoded()) 
            return segment.totalBlockCount;
        else
            return segment.dataBlockCount;
    }
    
    protected void onCompletedAll() {
        segment.onInsertedAllBlocks();
    }

}
