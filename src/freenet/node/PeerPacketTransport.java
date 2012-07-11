package freenet.node;

import freenet.pluginmanager.PacketTransportPlugin;
/**
 * This class will be used to store keys, timing fields, etc. by PeerNode for each transport for handshaking. 
 * Once handshake is completed a PeerPacketConnection object is used to store the session keys.<br><br>
 * 
 * <b>Convention:</b> The "Transport" word is used in fields that are transport specific, and are also present in PeerNode.
 * These fields will allow each Transport to behave differently. The existing fields in PeerNode will be used for 
 * common functionality.
 * The fields without "Transport" in them are those which in the long run must be removed from PeerNode.
 * <br> e.g.: <b>isTransportRekeying</b> is used if the individual transport is rekeying;
 * <b>isRekeying</b> will be used in common to all transports in PeerNode.
 * <br> e.g.: <b>jfkKa</b>, <b>incommingKey</b>, etc. should be transport specific and must be moved out of PeerNode 
 * once existing UDP is fully converted to the new TransportPlugin format.
 * @author chetan
 *
 */
public class PeerPacketTransport extends PeerTransport {
	
	protected final PacketTransportPlugin transportPlugin;
	
	protected final FNPPacketMangler packetMangler;
	
	/*
	 * Time related fields
	 */
	/** When did we last send a packet? */
	protected long timeLastSentTransportPacket;
	/** When did we last receive a packet? */
	protected long timeLastReceivedTransportPacket;
	/** When did we last receive a non-auth packet? */
	protected long timeLastReceivedTransportDataPacket;
	/** When did we last receive an ack? */
	protected long timeLastReceivedTransportAck;
	

	public PeerPacketTransport(PacketTransportPlugin transportPlugin, FNPPacketMangler packetMangler, PeerNode pn){
		super(transportPlugin, packetMangler, pn);
		this.transportPlugin = transportPlugin;
		this.packetMangler = packetMangler;
	}
	
	public PeerPacketTransport(PacketTransportBundle packetTransportBundle, PeerNode pn){
		this(packetTransportBundle.transportPlugin, packetTransportBundle.packetMangler, pn);
	}
}
