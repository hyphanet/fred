package freenet.node;

import freenet.pluginmanager.PacketTransportPlugin;

/**
 * A bundle of objects needed for connections. I could have implemented it using PeerTransport, but to keep the design simple
 * I have created a separate object for this. Can be expanded to include other common objects.
 * @author chetan
 *
 */
public class PacketTransportBundle {
	
	public final String transportName;
	
	public final PacketTransportPlugin transportPlugin;
	
	public final FNPPacketMangler packetMangler;
	
	public PacketTransportBundle(PacketTransportPlugin transportPlugin, FNPPacketMangler packetMangler){
		this.transportName = transportPlugin.transportName;
		this.transportPlugin = transportPlugin;
		this.packetMangler = packetMangler;
	}

}
