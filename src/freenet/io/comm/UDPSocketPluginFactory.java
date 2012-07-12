package freenet.io.comm;

import java.io.IOException;
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
import freenet.pluginmanager.TransportConfig;
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
	public PacketTransportPlugin makeTransportPlugin(TransportMode transportMode, TransportConfig config, IOStatisticCollector collector, long startupTime) throws TransportInitException {
		TransportConfigImpl address = (TransportConfigImpl) config;
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



	@Override
	public TransportConfig toTransportConfig(SimpleFieldSet config)	throws TransportPluginConfigurationException {
		try {
			return new TransportConfigImpl(config);
		} catch (UnknownHostException e) {
			throw new TransportPluginConfigurationException("Unknown host. Exception: " + e);
		}
	}

}

class TransportConfigImpl implements TransportConfig {
	
	public InetAddress inetAddress;
	public int portNumber;
	private SimpleFieldSet config;
	
	public TransportConfigImpl(SimpleFieldSet config) throws UnknownHostException {
		initialize(config);
	}
	
	private void initialize(SimpleFieldSet config) throws UnknownHostException {
		this.config = config;
		this.inetAddress = InetAddress.getByName(config.get("address"));
		this.portNumber = Integer.parseInt(config.get("port"));
	}

	@Override
	public SimpleFieldSet getConfig() {
		return config;
	}

	@Override
	public void writeConfig(SimpleFieldSet config) throws IOException {
		initialize(config);
	}
	
}

