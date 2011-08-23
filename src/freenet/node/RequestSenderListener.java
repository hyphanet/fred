package freenet.node;

/** All these methods should return quickly! */
public interface RequestSenderListener {
	/** Should return quickly, allocate a thread if it needs to block etc */
	void onReceivedRejectOverload();
	/** Should return quickly, allocate a thread if it needs to block etc */
	void onCHKTransferBegins();
	/** Should return quickly, allocate a thread if it needs to block etc */
	void onRequestSenderFinished(int status, boolean fromOfferedKey, RequestSender rs);
	/** Abort downstream transfers (not necessarily upstream ones, so not via the PRB).
	 * Should return quickly, allocate a thread if it needs to block etc. */
	void onAbortDownstreamTransfers(int reason, String desc);
}