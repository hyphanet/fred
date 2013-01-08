package freenet.pluginmanager;

import java.util.Set;

import freenet.io.comm.IOStatisticCollector;
import freenet.node.TransportManager.TransportMode;
import freenet.support.SimpleFieldSet;

/**
 * A plugin must implement this interface and must register an instance with the transportManager.
 * The plugin will be used whenever needed.
 * @author chetan
 *
 */
public interface StreamTransportPluginFactory {
	
	/**
	 * The plugin instance and this method must return the same value. 
	 * Else we throw an an exception and call invalidTransportCallback method.
	 * @return The transport name of the plugin.
	 */
	public String getTransportName();
	
	/**
	 * Get a list of operative modes
	 */
	public Set<TransportMode> getOperationalModes();
	
	/**
	 * Method to make the plugin.
     * FredPlugin should help with that.
     * @param transportMode Mode of operation (opennet,darknet,etc)
	 * @param config The config is used to create the bindings.
	 * @param collector If plugin wants to support sharing statistics, then the object can be used
	 * @return The plugin that can be be used.
	 */
	public StreamTransportPlugin makeTransportPlugin(TransportMode transportMode, SimpleFieldSet config, IOStatisticCollector collector, long startupTime) throws TransportInitException;;
	
	/**
	 * The plugin instance was faulty. We leave it to the plugin to fix the issue and register again if it wants to.
	 * The transport is not being used, even though the plugin is loaded.
	 */
	public void invalidTransportCallback(FaultyTransportPluginException e);
	
}
