package freenet.io.comm;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

import freenet.node.Node;
import freenet.node.TransportManager.TransportMode;
import freenet.pluginmanager.FaultyTransportPluginException;
import freenet.pluginmanager.PacketTransportPlugin;
import freenet.pluginmanager.PacketTransportPluginFactory;
import freenet.pluginmanager.TransportInitException;
import freenet.pluginmanager.TransportPluginConfigurationException;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class UDPSocketPluginFactory implements PacketTransportPluginFactory {
	
	private final Node node;
	public static final String ADDRESS = "address";
	public static final String PORT = "port";
	
	public UDPSocketPluginFactory(Node node){
		this.node = node;
	}
	
	

	@Override
	public String getTransportName() {
		return Node.defaultPacketTransportName;
	}

	@Override
	public PacketTransportPlugin makeTransportPlugin(TransportMode transportMode, SimpleFieldSet config, IOStatisticCollector collector, long startupTime) throws TransportInitException, TransportPluginConfigurationException {
		TransportConfig address = toTransportConfig(config);
		String title = "UDP " + (transportMode == TransportMode.opennet ? "Opennet " : "Darknet ") + "port " + address.portNumber;
		try {
			return new UdpSocketHandler(transportMode, address.portNumber, address.inetAddress, node, startupTime, title, node.collector);
		} catch (SocketException e) {
			Logger.error(this, "UDP was not created", e);
			return null;	
		}
	}

	@Override
	public void invalidTransportCallback(FaultyTransportPluginException e) {
		//Do nothing
	}

	@Override
	public Set<TransportMode> getOperationalModes() {
		HashSet<TransportMode> h = new HashSet<TransportMode>();
		h.add(TransportMode.darknet);
		h.add(TransportMode.opennet);
		return h;
	}

	private TransportConfig toTransportConfig(SimpleFieldSet config) throws TransportPluginConfigurationException {
		try {
			return new TransportConfig(config);
		} catch (UnknownHostException e) {
			throw new TransportPluginConfigurationException("Unknown host. Exception: " + e);
		}
	}

}

class TransportConfig {
	
	public InetAddress inetAddress;
	public int portNumber;
	
	public TransportConfig(SimpleFieldSet config) throws UnknownHostException {
		this.inetAddress = InetAddress.getByName(config.get("address"));
		this.portNumber = Integer.parseInt(config.get("port"));
	}
}

