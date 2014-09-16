package freenet.client.async;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import freenet.keys.NodeCHK;
import freenet.support.Logger;
import freenet.support.io.StorageFormatException;

/** Tracks which blocks have been completed, how many attempts have been made for which blocks,
 * allows choosing a random block, failing a block etc.
 * @author toad
 */
public class SimpleBlockChooser {
    
    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;
    static {
        Logger.registerClass(SimpleBlockChooser.class);
    }

    private final int blocks;
    private final boolean[] completed;
    private int completedCount;
    private final int[] retries;
    protected final int maxRetries;
    private final Random random;
    
    public SimpleBlockChooser(int blocks, Random random, int maxRetries) {
        this.maxRetries = maxRetries;
        this.blocks = blocks;
        this.random = random;
        this.completed = new boolean[blocks];
        this.retries = new int[blocks];
    }
    
    /** Choose a key to fetch, taking into account retries */
    public synchronized int chooseKey() {
        int max = getMaxBlockNumber();
        int[] candidates = new int[max];
        int count = 0;
        int minRetryCount = Integer.MAX_VALUE;
        for(int i=0;i<max;i++) {
            int retry = retries[i];
            if(retry > maxRetries && maxRetries != -1) continue;
            if(retry > minRetryCount) continue;
            if(!checkValid(i)) continue;
            if(retry < minRetryCount) {
                count = 0;
                candidates[count++] = i;
                minRetryCount = retry;
            } else if(retry == minRetryCount) {
                candidates[count++] = i;
            } // else continue;
        }
        if(count == 0) {
            return -1;
        } else {
            return candidates[random.nextInt(count)];
        }
    }

    public boolean onNonFatalFailure(int blockNo) {
        return isFatalRetries(innerOnNonFatalFailure(blockNo));
    }
    
    private boolean isFatalRetries(int retries) {
        if(maxRetries == -1) return false;
        return retries > maxRetries;
    }

    /** Notify when a block has failed.
     * @return The total number of attempts for the block so far. Some callers (e.g. inserter) may
     * fail after a single terminal failure, others after some number of failures (e.g. getter), so
     * we leave this to the caller. */
    protected synchronized int innerOnNonFatalFailure(int blockNo) {
        return ++retries[blockNo];
    }
    
    /** Notify when a block has succeeded. */
    public boolean onSuccess(int blockNo) {
        synchronized(this) {
            if(completed[blockNo]) return false;
            completed[blockNo] = true;
            completedCount++;
            if(completedCount < blocks) {
                if(logMINOR) Logger.minor(this, "Completed blocks: "+completedCount+"/"+blocks);
                return true;
            }
        }
        onCompletedAll();
        return true;
    }
    
    /** Notify that a block has no longer succeeded. E.g. we downloaded it but now the data is no
     * longer available due to disk corruption.
     * @param blockNo
     */
    public synchronized void onUnSuccess(int blockNo) {
        if(!completed[blockNo]) return;
        completed[blockNo] = false;
        completedCount--;
    }
    
    protected void onCompletedAll() {
        // Do nothing.
    }

    /** Is the proposed block valid? Override to implement custom logic e.g. checking which 
     * requests are already running. */
    protected boolean checkValid(int chosen) {
        return !completed[chosen];
    }

    /** Can be overridden to restrict chooseKey() to a subset of the available blocks. Useful for
     * inserts where we will be able to insert all the blocks until after encoding has finished.
     * @return The upper bound on the block number chosen.
     */
    protected int getMaxBlockNumber() {
        return blocks;
    }

    /** Mass replace of success/failure. Used by fetchers when we try to decode and fail, possibly 
     * because of disk corruption.
     * @param used An array of flags indicating whether we have each block.
     * @return The number of blocks in used.
     */
    public synchronized void replaceSuccesses(boolean[] used) {
        for(int i=0;i<blocks;i++) {
            if(used[i] && !completed[i])
                onSuccess(i);
            else if(!used[i] && completed[i])
                onUnSuccess(i);
        }
    }

    public synchronized int successCount() {
        return completedCount;
    }
    
    public synchronized int getRetries(int blockNumber) {
        return retries[blockNumber];
    }

    /** Ugly to include this here, but avoids making completed visible ... */
    public synchronized int getBlockNumber(SplitFileSegmentKeys keys, NodeCHK key) {
        return keys.getBlockNumber(key, completed);
    }
    
    public synchronized boolean hasSucceeded(int blockNumber) {
        return completed[blockNumber];
    }

    /** Write the retry counts only, and only if maxRetries != -1. Used if the caller will manage
     * persistence for the actual list of blocks fetched, as in SplitFileFetcherSegment.
     * @throws IOException */
    public void writeRetries(DataOutputStream dos) throws IOException {
        if(maxRetries == -1) return;
        for(int retry : retries)
            dos.writeInt(retry);
    }

    public void readRetries(DataInputStream dis) throws IOException {
        if(maxRetries == -1) return;
        for(int i=0;i<blocks;i++)
            retries[i] = dis.readInt();
    }
    
    static final int VERSION = 1;

    /** Write everything 
     * @throws IOException */
    public void write(DataOutputStream dos) throws IOException {
        dos.writeInt(VERSION);
        for(boolean b : completed)
            dos.writeBoolean(b);
        dos.writeInt(maxRetries);
        writeRetries(dos);
    }
    
    public void read(DataInputStream dis) throws StorageFormatException, IOException {
        if(dis.readInt() != VERSION) throw new StorageFormatException("Bad version in block chooser");
        for(int i=0;i<completed.length;i++) {
            completed[i] = dis.readBoolean();
            if(completed[i]) completedCount++;
        }
        if(dis.readInt() != maxRetries) throw new StorageFormatException("Max retries has changed");
        readRetries(dis);
    }

    public synchronized int countFailedBlocks() {
        if(maxRetries == -1) return 0;
        int total = 0;
        for(int i=0;i<retries.length;i++) {
            if(completed[i]) continue;
            if(retries[i] > maxRetries) total++;
        }
        return total;
    }

    public synchronized boolean[] copyDownloadedBlocks() {
        return completed.clone();
    }
    
    public synchronized int countFetchable() {
        int x = 0;
        for(int i=0;i<blocks;i++) {
            if(retries[x] >= maxRetries) continue;
            if(!checkValid(x)) continue;
            if(!completed[x]) x++;
        }
        return x;
    }
    
    public synchronized boolean hasSucceededAll() {
        return completedCount == blocks;
    }

}
