package freenet.node;

interface InsertSenderListener {
    /** Called when the insert finishes. Called off-thread so safe to block etc. */
    void onInsertSenderFinished(int status, CHKInsertSender sender);
    /** Called when completed() becomes true */
    void onCompletion(CHKInsertSender sender);
}
