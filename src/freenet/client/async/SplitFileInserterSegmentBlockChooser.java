package freenet.client.async;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import freenet.client.async.SplitFileInserterSegmentStorage.BlockInsert;
import freenet.node.KeysFetchingLocally;
import freenet.support.io.StorageFormatException;

/** Tracks retry count and completion status for blocks in an insert segment. */
public class SplitFileInserterSegmentBlockChooser extends SimpleBlockChooser {
    
    final SplitFileInserterSegmentStorage segment;
    final KeysFetchingLocally keysFetching;
    final int[] consecutiveRNFs;
    /** If positive, this many RNFs count as success. */
    final int consecutiveRNFsCountAsSuccess;

    public SplitFileInserterSegmentBlockChooser(SplitFileInserterSegmentStorage segment, 
            int blocks, Random random, int maxRetries, KeysFetchingLocally keysFetching,
            int consecutiveRNFsCountAsSuccess) {
        super(blocks, random, maxRetries);
        this.segment = segment;
        this.keysFetching = keysFetching;
        this.consecutiveRNFsCountAsSuccess = consecutiveRNFsCountAsSuccess;
        if(consecutiveRNFsCountAsSuccess > 0)
            consecutiveRNFs = new int[blocks];
        else
            consecutiveRNFs = null;
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
    
    protected boolean checkValid(int chosen) {
        if(!super.checkValid(chosen)) return false;
        return !keysFetching.hasInsert(new BlockInsert(segment, chosen));
    }

    /** Handle an RNF if the n-consecutive-RNFs-count-as-success hack is enabled.
     * Must only be called if consecutiveRNFsCountAsSuccess > 0 */
    public void onRNF(int blockNo) {
        synchronized(this) {
            assert(consecutiveRNFsCountAsSuccess > 0);
            if(++consecutiveRNFs[blockNo] < consecutiveRNFsCountAsSuccess) return;
        }
        onSuccess(blockNo);
    }

    /** Count the previous RNFs towards the retry limit, when the following error wasn't an RNF. */
    public synchronized boolean pushRNFs(int blockNo) {
        int ret = consecutiveRNFs[blockNo];
        consecutiveRNFs[blockNo] = 0;
        for(int i=0;i<ret;i++)
            if(onNonFatalFailure(blockNo)) return true;
        return false;
    }
    
    public void write(DataOutputStream dos) throws IOException {
        super.write(dos);
        if(consecutiveRNFsCountAsSuccess > 0) {
            for(int i : consecutiveRNFs)
                dos.writeInt(i);
        }
    }
    
    public void read(DataInputStream dis) throws IOException, StorageFormatException {
        super.read(dis);
        if(consecutiveRNFsCountAsSuccess > 0) {
            for(int i=0;i<consecutiveRNFs.length;i++) {
                consecutiveRNFs[i] = dis.readInt();
            }
        }
        
    }
}
