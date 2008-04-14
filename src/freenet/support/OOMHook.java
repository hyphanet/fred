package freenet.support;

/**
 * @author sdiz
 */
public interface OOMHook {
	/**
	 * Handle OutOfMemoryError
	 * 
	 * (try to free some cache, save the files, etc).
	 */
	void handleOOM() throws Exception;
}
