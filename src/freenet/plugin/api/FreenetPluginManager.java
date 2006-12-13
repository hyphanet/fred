package freenet.plugin.api;

import java.util.Random;

/**
 * The plugin's interface to the Freenet node.
 */
public interface FreenetPluginManager {

	/** Node version */
	public long version();
	
	/** Node SVN version, if known, otherwise -1 */
	public long internalVersion();
	
	/** Random number generator */
	public Random random();
	
	
}
