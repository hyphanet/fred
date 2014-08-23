package freenet.client.async;

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
    void onSucceeded();

}
