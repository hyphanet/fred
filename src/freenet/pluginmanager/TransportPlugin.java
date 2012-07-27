package freenet.pluginmanager;

import java.net.UnknownHostException;
import java.util.List;

import freenet.node.Node;
import freenet.node.TransportManager.TransportMode;

/**
 * Base class for all transports
 * @see PacketTransportPlugin
 * @see StreamTransportPlugin
 * @author chetan
 *
 */
public abstract class TransportPlugin implements Runnable {
	
	/**Unique name for every transport*/
	public final String transportName;
	
	public enum TransportType{
		streams, packets
	}
  	public final TransportType transportType;
	
	/**
	 * Initialize the mode in which the instance of the plugin is to
     * work in: opennet or darknet
	 */
	public final TransportMode transportMode;
	
	public final Node node;
	
	public TransportPlugin(TransportType transportType, String transportName, TransportMode transportMode, Node node){
		this.transportType = transportType;
		this.transportName = transportName;
		this.transportMode = transportMode;
		this.node = node;
	}
	
	/**
	 * To start listening and do whatever it needs to.
	 */
	public abstract void startPlugin();
	
	/**Method to pause a plugin, not terminate it.  
	 * Can be used for temporarily stopping a plugin, or putting it to sleep state.
	 * Don't know if this method is really necessary. 
	 * But it would provide an implementation that could effectively handle stopping traffic, still keeping peers alive.
	 * Users can start using this unnecessarily, affecting freenet. Therefore it is available only for plugins.
	 * Default transports such as existing UDP (or simple TCP in the future) which are run inside fred cannot be paused.
	 * Others might have a requirement. Otherwise this method should not be implemented to benefit users.  
	 * @return true if successful
	 */
	public abstract boolean pauseTransportPlugin();
	
	/** To resume a stopped plugin*/
	public abstract boolean resumeTransportPlugin();

	/** The PluginAddress that can be used in the noderef, to let our peers know */
	public abstract List<PluginAddress> getPluginAddress();
	
	/**
	 * To stop and disconnect. Can be used to unload plugin as well as only disable using TransportManager
	 */
	public abstract void stopPlugin();
	
	/**
	 * This method must convert an address from the noderef to the type PluginAddress.
	 * 
	 * @param address
	 * @return
	 */
	public abstract PluginAddress toPluginAddress(String address) throws MalformedPluginAddressException;
	
}

