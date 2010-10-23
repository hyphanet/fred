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

	/** The maximum size of a packet, not including transport layer headers */
	int getMaxPacketSize();

	/**
	 * Send a block of encoded bytes to a peer. This is called by
	 * send, and by IncomingPacketFilter.processOutgoing(..).
     * @param blockToSend The data block to send.
     * @param destination The peer to send it to.
     */
    public void sendPacket(byte[] blockToSend, Peer destination, boolean allowLocalAddresses) throws LocalAddressException;

    /**
     * Get the size of the transport layer headers, for byte accounting purposes.
     */
	public int getHeadersLength();

	/** Set the decryption filter to which incoming packets will be fed */
	public void setLowLevelFilter(IncomingPacketFilter f);

	/** How big must the pending data be before we send a packet? *Includes* transport layer headers. */
	public int getPacketSendThreshold();

	/** Does this port appear to be port forwarded? @see AddressTracker */
	Status getDetectedConnectivityStatus();

}
