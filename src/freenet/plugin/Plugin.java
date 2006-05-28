package freenet.plugin;

/**
 * Interface for Fred plugins.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public interface Plugin {

	/**
	 * Returns the name of the plugin.
	 * 
	 * @return The name of the plugin
	 */
	public String getPluginName();

	/**
	 * Sets the plugin manager that manages this plugin.
	 * 
	 * @param pluginManager
	 *            The plugin manager
	 */
	public void setPluginManager(PluginManager pluginManager);

	/**
	 * Starts the plugin. If the plugin needs threads they have to be started
	 * here.
	 */
	public void startPlugin();

	/**
	 * Stops the plugin.
	 */
	public void stopPlugin();

}
