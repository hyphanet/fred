/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import freenet.io.AddressTracker.Status;
import freenet.io.comm.Peer.LocalAddressException;

/**
 * Base class for UdpSocketHandler and any other datagram-based transports.
 */
public interface PacketSocketHandler extends SocketHandler {

	/** The maximum size of a packet, not including transport layer headers.
	 * @param ipv6 If true, sending an IPv6 packet. If false, sending an IPv4 packet.
     * FIXME Long term (with packet transports), we should use a separate transport for IPv6 vs IPv4. */
	int getMaxPacketSize(boolean ipv6);

	/**
	 * Send a block of encoded bytes to a peer. This is called by
	 * send, and by IncomingPacketFilter.processOutgoing(..).
     * @param blockToSend The data block to send.
     * @param destination The peer to send it to.
     */
    public void sendPacket(byte[] blockToSend, Peer destination, boolean allowLocalAddresses) throws LocalAddressException;

    /**
     * Get the size of the transport layer headers, for byte accounting purposes.
     * @param ipv6 If true, sending an IPv6 packet. If false, sending an IPv4 packet.
     * FIXME Long term (with packet transports), we should use a separate transport for IPv6 vs IPv4.
     */
	public int getHeadersLength(boolean ipv6);

    /**
     * Get the size of the transport layer headers, for byte accounting purposes.
	 * @param peer used to detect address family.
     */
	public int getHeadersLength(Peer peer);

	/** Set the decryption filter to which incoming packets will be fed */
	public void setLowLevelFilter(IncomingPacketFilter f);

	/** How big must the pending data be before we send a packet? *Includes* transport layer headers. */
	public int getPacketSendThreshold();

	/** Does this port appear to be port forwarded? @see AddressTracker */
	Status getDetectedConnectivityStatus();

}
