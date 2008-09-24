/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.keys.FreenetURI;

/**
 * Interface for plugins thats can be updated over freenet
 * 
 * 
 * @author saces
 */
public interface FredPluginUoF {
	
	/**
	 * If the uri fetches any data the plugin is revoked<ul><li>plugin will be stopped and disabled</li>
	 * <li>stop updater</li></ul>
	 * 
	 * @return FreenetURI to watch
	 */
	public FreenetURI getRevokeURI();
	
	/**
	 * an uri that points to plugin jarfile
	 * @return FreenetURI to look for next edition
	 */
	public FreenetURI getUpdaterURI();
}
