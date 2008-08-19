package freenet.pluginmanager;

import java.util.Set;

/**
 * Interface for port forwarding plugins.
 * @author toad
 */
public interface FredPluginPortForward {

	/**
	 * Called when Fred's list of public ports changes, and just after loading the
	 * plugin.
	 * @param ports The set of ports that need to be forwarded from the outside 
	 * world through the NAT or firewall.
	 * @param cb Callback to be called with success/failure of each forward. Some
	 * plugins may return a probabilistic success e.g. with UP&P.
	 */
	public void onChangePublicPorts(Set<ForwardPort> ports, ForwardPortCallback cb);
	
}
