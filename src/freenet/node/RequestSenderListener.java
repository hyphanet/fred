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
	/** Not called by RequestSender, but called if localOnly is true and the data
	 * is not in the store. 
	 * @param internalError If true, something broke severely. */
	void onNotStarted(boolean internalError);
	/** Not called by RequestSender, but called if the data was in the store. 
	 * tripPendingKey should already have been called. */
	void onDataFoundLocally();
}