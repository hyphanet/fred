package freenet.support;

/**
 * @author sdiz
 */
public interface OOMHook {
	/**
	 * Handle running low of memory
	 * 
	 * (try to free some cache, save the files, etc).
	 */
	void handleLowMemory() throws Exception;

	/**
	 * Handle running out of memory
	 */
	void handleOutOfMemory() throws Exception;
}
