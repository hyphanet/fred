package freenet.client;

/**
 * Object passed to RetryTracker. This is called when RetryTracker finishes.
 */
public interface RetryTrackerCallback {

	/**
	 * Notify the caller that we have finished.
	 * @param succeeded The blocks which succeeded.
	 * @param failed The blocks which failed.
	 * @param fatalErrors The blocks which got fatal errors.
	 */
	void finished(SplitfileBlock[] succeeded, SplitfileBlock[] failed, SplitfileBlock[] fatalErrors);

	/**
	 * When a block completes etc.
	 */
	void onProgress();

}
