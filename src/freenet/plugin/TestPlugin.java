/**
 * 
 */
package freenet.plugin;

/**
 * Test plugin. Does absolutely nothing.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class TestPlugin implements Plugin {

	/**
	 * @see freenet.plugin.Plugin#getPluginName()
	 */
	public String getPluginName() {
		return "Simple Test Plugin";
	}

	/**
	 * @see freenet.plugin.Plugin#setPluginManager(freenet.plugin.PluginManager)
	 */
	public void setPluginManager(PluginManager pluginManager) {
	}

	/**
	 * @see freenet.plugin.Plugin#startPlugin()
	 */
	public void startPlugin() {
	}

	/**
	 * @see freenet.plugin.Plugin#stopPlugin()
	 */
	public void stopPlugin() {
	}

}
