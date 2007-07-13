/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

public interface FredPlugin {
	// HTTP-stuff has been moved to FredPluginHTTP
	
	public void terminate();
	
	/** Run the plugin. Called after node startup. Should be able to access queue etc at this point. */
	public void runPlugin(PluginRespirator pr);
}
