package freenet.pluginmanager;

import freenet.io.comm.PacketSocketHandler;
import freenet.node.Node;
import freenet.node.TransportManager.TransportMode;

public abstract class PacketTransportPlugin extends TransportPlugin implements PacketSocketHandler{
	
	public PacketTransportPlugin(String transportName, TransportMode transportMode, Node node) {
		super(TransportType.packets, transportName, transportMode, node);
	}

}
