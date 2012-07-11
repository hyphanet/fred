package freenet.node;

import freenet.pluginmanager.PluginAddress;
import freenet.pluginmanager.StreamTransportPlugin;

/**
 * This object will be used by PeerNode to maintain sessions keys for various transports. 
 * A list of PeerConnection will be maintained by PeerNode to handle multiple transports 
 * @author chetan
 *
 */
public class PeerStreamConnection extends PeerConnection {
	
	/** The transport this connection is using. */
	protected final StreamTransportPlugin transportPlugin;
	
	/** Mangler to handle connections for different transports */
	protected final OutgoingStreamMangler streamMangler;
	
	/** The object that runs this connection. Analogous to NewPacketFormat and PacketSender */
	protected final StreamConnectionFormat streamConnection;
	
	public PeerStreamConnection(PeerNode pn, StreamTransportPlugin transportPlugin, OutgoingStreamMangler streamMangler, StreamConnectionFormat streamConnection, PluginAddress detectedTransportAddress){
		super(transportPlugin.transportName, pn);
		this.transportPlugin = transportPlugin;
		this.streamMangler = streamMangler;
		this.streamConnection = streamConnection;
		this.detectedTransportAddress = detectedTransportAddress;
	}
	

}
