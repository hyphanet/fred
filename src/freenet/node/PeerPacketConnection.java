package freenet.node;


import freenet.pluginmanager.PacketTransportPlugin;
import freenet.pluginmanager.PluginAddress;

/**
 * This object will be used by PeerNode to maintain sessions keys for various transports. 
 * A list of PeerConnection will be maintained by PeerNode to handle multiple transports 
 * @author chetan
 *
 */
public class PeerPacketConnection extends PeerConnection {
	

	/** The transport this connection is using. */
	protected final PacketTransportPlugin transportPlugin;
	
	/** Mangler to handle connections for different transports */
	protected final FNPPacketMangler packetMangler;
	
	protected final NewPacketFormat packetFormat;
	
	
	public PeerPacketConnection(PeerNode pn, PacketTransportPlugin transportPlugin, FNPPacketMangler packetMangler, NewPacketFormat packetFormat, PluginAddress detectedTransportAddress){
		super(transportPlugin.transportName, pn);
		this.transportPlugin = transportPlugin;
		this.packetMangler = packetMangler;
		this.packetFormat = packetFormat;
		this.detectedTransportAddress = detectedTransportAddress;
	}

}
