package freenet.client.async;

import java.io.IOException;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.crypt.ChecksumFailedException;
import freenet.keys.ClientCHKBlock;
import freenet.node.BaseSendableGet;

/** Callback used by SplitFileFetcherStorage. Arguably this is over-abstraction purely to make unit
 * tests easier without having to use Mockito (which presumably means solving the issues with 
 * Maven). OTOH maybe it has some other use ... FIXME reconsider.
 * @author toad
 */
public interface SplitFileFetcherStorageCallback {

    /** Called when the splitfile has been successfully downloaded and decoded. E.g. 
     * streamGenerator() should work now. However the splitfile storage layer may still need the
     * data to e.g. encode healing blocks, so it cannot be freed until close(). The higher level
     * code (e.g. SplitFileFetcher) must call finishedFetcher() when finished, and when that
     * has been called *and* the storage layer has finished, it will close and free the underlying 
     * storage and call close() here. */
    void onSuccess();
    
    /** Get the priority class of the request. Needed for e.g. FEC decoding scheduling. */
    short getPriorityClass();

    /** Called when the splitfile storage layer receives an unrecoverable disk I/O error. */
    void failOnDiskError(IOException e);

    /** Called when the splitfile storage layer receives unrecoverable data corruption. */
    void failOnDiskError(ChecksumFailedException e);

    /** Called during construction to tell other layers how many blocks to expect.
     * @param requiredBlocks The number of blocks that must be fetched to complete the download.
     * @param remainingBlocks The total number of blocks minus requiredBlocks.
     */
    void setSplitfileBlocks(int requiredBlocks, int remainingBlocks);

    /**
     * Called during construction, when we know the settings for the splitfile.
     * @param min The lowest CompatibilityMode that appears to be valid based on what we've fetched so far.
     * @param max The highest CompatibilityMode that appears to be valid based on what we've fetched so far.
     * @param customSplitfileKey The fixed byte[] encryption key used on insert. On anything recent, we generate a single key, randomly for an SSK,
     * or based on the content for a CHK, and use it for everything. This saves metadata space and improves security for SSKs.
     * @param compressed Whether the content is compressed. If false, the dontCompress option was used.
     * @param bottomLayer Whether this report originates at the bottom layer of the splitfile pyramid. I.e. the actual file, not the file containing
     * the metadata to fetch the file (this can recurse for several levels!)
     * @param definitiveAnyway Whether this report is definitive even though it's not from the bottom layer. This is true of recent splitfiles, 
     * where we store all the data in the top key.
     */
    void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] customSplitfileKey, boolean compressed, boolean bottomLayer, boolean definitiveAnyway);

    /** Queue a block to be healed. LOCKING: Called on the decode thread, so should avoid taking 
     * any dangerous locks and not be too slow. */
    void queueHeal(byte[] data, byte[] cryptoKey, byte cryptoAlgorithm);

    /** Called when the storage layer has finished, the higher level code has finished, and the 
     * storage has been freed, i.e. the request is now completely finished. */
    void onClosed();

    void onFetchedBlock();
    
    /** Called when the splitfile fetcher gives up on a block. (Assumed to be a non-fatal error,
     * run out of retries) */
    void onFailedBlock();

    void onResume(int succeededBlocks, int failedBlocks, ClientMetadata mimeType, long finalSize);

    /** Called when the fetch failed, e.g. due to running out of retries. */
    void fail(FetchException fetchException);

    /** Called whenever we successfully download, decode or encode a block and it matches the 
     * expected key. LOCKING: Called on the decode thread so should avoid taking any dangerous
     * locks. */
    void maybeAddToBinaryBlob(ClientCHKBlock decodedBlock);

    /** Do we want maybeAddToBinaryBlob() to be called?? LOCKING: Should not take any locks. */
    boolean wantBinaryBlob();

    /** Can be null. Provided mainly for KeysFetchingLocally. */
    BaseSendableGet getSendableGet();

    /** Called when we recover from disk corruption, and have to re-download some blocks that we
     * had already downloaded but which were corrupted on disk. E.g. when a segment attempts to 
     * decode but discovers that a block doesn't match the key given. */
    void restartedAfterDataCorruption();

    /** Called when the fetcher may have exited cooldown early. */
    void clearCooldown();

    /** Called when the wakeup time reduces but it is still not fetchable. */
    void reduceCooldown(long wakeupTime);

    /** Can be null. Provided for KeyListeners. */
    HasKeyListener getHasKeyListener();

    KeySalter getSalter();

}
