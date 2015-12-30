/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

/** All the FredPlugin* APIs must be implemented by the main class - the class that implements 
 * FredPlugin, because that's where we look for them when loading a plugin. That allows us to 
 * automatically register the plugin for whichever service it is using. */
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
