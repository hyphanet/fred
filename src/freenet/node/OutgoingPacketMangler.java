/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.SocketHandler;
import freenet.support.ShortBuffer;
import freenet.support.WouldBlockException;

/**
 * Low-level interface for sending packets.
 * A UDP-based transport will have to implement both this and IncomingPacketFilter, usually
 * on the same class. 
 * @see freenet.io.comm.IncomingPacketFilter
 * @see freenet.node.FNPPacketMangler
 */
public interface OutgoingPacketMangler {

	/**
	 * Build one or more packets and send them, from a whole bunch of messages.
	 * If any MessageItem's are formatted already, they will be sent as single packets.
	 * Any packets which cannot be sent will be requeued on the PeerNode.
	 */
	public void processOutgoingOrRequeue(MessageItem[] messages, PeerNode pn,
			boolean neverWaitForPacketNumber, boolean dontRequeue);

	/**
	 * Resend a single packet.
	 */
	public void resend(ResendPacketItem item) throws PacketSequenceException, WouldBlockException, KeyChangedException, NotConnectedException;
	
	/**
	 * Build a packet and send it. From a Message recently converted into byte[],
	 * but with no outer formatting.
	 * @throws PacketSequenceException 
	 * @throws WouldBlockException 
	 */
	public void processOutgoing(byte[] buf, int offset, int length,
			KeyTracker tracker, int alreadyReportedBytes)
			throws KeyChangedException, NotConnectedException,
			PacketSequenceException, WouldBlockException;

	/**
	 * Encrypt a packet, prepend packet acks and packet resend requests, and send it. 
	 * The provided data is ready-formatted, meaning that it already has the message 
	 * length's and message counts.
	 * @param buf Buffer to read data from.
	 * @param offset Point at which to start reading.
	 * @param length Number of bytes to read.
	 * @param tracker The KeyTracker to use to encrypt the packet and send it to the
	 * associated PeerNode.
	 * @param packetNumber If specified, force use of this particular packet number.
	 * Means this is a resend of a dropped packet.
	 * @throws NotConnectedException If the node is not connected.
	 * @throws KeyChangedException If the primary key changes while we are trying to send this packet.
	 * @throws PacketSequenceException 
	 * @throws WouldBlockException If we cannot allocate a packet number because it would block.
	 */
	public void processOutgoingPreformatted(byte[] buf, int offset, int length,
			KeyTracker tracker, int packetNumber,
			AsyncMessageCallback[] callbacks, int alreadyReportedBytes)
			throws KeyChangedException, NotConnectedException,
			PacketSequenceException, WouldBlockException;

	/**
	 * Send a handshake, if possible, to the node.
	 * @param pn
	 */
	public void sendHandshake(PeerNode pn);

	/**
	 * Is a peer disconnected?
	 */
	public boolean isDisconnected(PeerContext context);
	
	/**
	 * List of supported negotiation types in preference order (best last)
	 */
	public int[] supportedNegTypes();
	
	/**
	 * Size of the packet headers, in bytes, assuming only one message in this packet.
	 */
	public int fullHeadersLengthOneMessage();

	/**
	 * The SocketHandler we are connected to.
	 */
	public SocketHandler getSocketHandler();

	/**
	 * Get our addresses, as peers.
	 */
	public Peer[] getPrimaryIPAddress();

	/**
	 * Get our compressed noderef
	 */
	public byte[] getCompressedNoderef();
	
	/**
	 * Always allow local addresses?
	 */
	public boolean alwaysAllowLocalAddresses();
}
