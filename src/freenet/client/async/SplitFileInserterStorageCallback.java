package freenet.client.async;

import freenet.client.InsertException;
import freenet.client.Metadata;

/** Callback interface for a SplitFileInserterStorage. Usually implemented by SplitFileInserter,
 * but can be used for unit tests etc too. Hence SplitFileInserter doesn't need to know much about
 * the rest of the client layer.
 * @author toad
 */
public interface SplitFileInserterStorageCallback {

    /** All the segments (and possibly cross-segments) have been encoded. */
    void onFinishedEncode();

    /** Encoded another segment. Caller might need to reschedule as there are more blocks available
     * to insert. */
    void encodingProgress();

    /** Called when all blocks have keys. Callback must decide whether to complete / start the next
     * insert level, or whether to wait for all the blocks to insert. */
    void onHasKeys();

    /** Called when the whole insert has succeeded, i.e. when all blocks have been inserted. */
    void onSucceeded(Metadata metadata);

    /** Called if the insert fails. All encodes will have finished by the time this is called. */
    void onFailed(InsertException e);

    /** Called when a block is inserted successfully */
    void onInsertedBlock();

    /** Called when a block becomes fetchable (unless because of an encode, in which case we only 
     * call encodingProgress() ) */
    void clearCooldown();

}
