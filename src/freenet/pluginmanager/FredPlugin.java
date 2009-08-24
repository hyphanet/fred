/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

public interface FredPlugin {
	// HTTP-stuff has been moved to FredPluginHTTP
	
	/** Shut down the plugin. */
	public void terminate();
	
	/** Run the plugin. Called after node startup. Should be able to access 
	 * queue etc at this point. Plugins which do not implement 
	 * FredPluginThreadless will be terminated after they return from this 
	 * function. Threadless plugins will not terminate until they are 
	 * explicitly unloaded. */
	public void runPlugin(PluginRespirator pr);
}
