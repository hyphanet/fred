/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.plugin.api;

import freenet.support.api.BaseSubConfig;

/**
 * Indicates that the plugin wants to use (read, write) persistent config settings. These will be saved to the 
 * freenet.ini file under the plugin's entry.
 */
public interface NeedsConfig {

	/**
	 * Called by the node when loading the plugin. Allows the plugin to register persistent config settings, and
	 * read their current values. Note that config settings only persist if the plugin is still loaded; once it is
	 * unloaded, they are lost.
	 * @param config A BaseSubConfig object on which the plugin can register config settings.
	 */
	public void register(BaseSubConfig config);

}
