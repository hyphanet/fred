package freenet.node;

import java.util.Vector;

import freenet.pluginmanager.StreamTransportPlugin;
/**
 * This class will be used to store keys, timing fields, etc. by PeerNode for each transport for handshaking. 
 * Once handshake is completed a PeerStreamConnection object is used to store the session keys.<br><br>
 * 
 * Unlike the PeerPacketTransport this object will be used differently. It will have a StreamMangler and StreamFormat.
 * It will be driven by separate threads and not by PacketSender.
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
public class PeerStreamTransport extends PeerTransport {
	
	protected final StreamTransportPlugin transportPlugin;
	
	protected final OutgoingStreamMangler streamMangler;
	
	protected StreamConnectionFormat streamConnection;
	
	/*
	 * Time related fields
	 */
	/** When did we last send a Stream? */
	protected long timeLastSentTransportStream;
	/** When did we last receive a Stream? */
	protected long timeLastReceivedTransportStream;
	/** When did we last receive a non-auth Stream? */
	protected long timeLastReceivedTransportDataStream;
	/** When did we last receive an ack? */
	protected long timeLastReceivedTransportAck;
	
	
	public PeerStreamTransport(StreamTransportPlugin transportPlugin, OutgoingStreamMangler streamMangler, PeerNode pn){
		super(transportPlugin, streamMangler, pn);
		this.transportPlugin = transportPlugin;
		this.streamMangler = streamMangler;
	}
	
	public PeerStreamTransport(StreamTransportBundle streamTransportBundle, PeerNode pn){
		this(streamTransportBundle.transportPlugin, streamTransportBundle.streamMangler, pn);
	}
	
}