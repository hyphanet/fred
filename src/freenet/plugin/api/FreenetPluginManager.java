/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
