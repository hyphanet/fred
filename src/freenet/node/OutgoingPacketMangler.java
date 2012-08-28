/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.Peer;
import freenet.io.comm.SocketHandler;
import freenet.pluginmanager.PacketTransportPlugin;

/**
 * Low-level interface for sending packets.
 * A UDP-based transport will have to implement both this and IncomingPacketFilter, usually
 * on the same class. 
 * @see freenet.io.comm.IncomingPacketFilter
 * @see freenet.node.FNPPacketMangler
 */
public interface OutgoingPacketMangler extends OutgoingMangler {

	/**
	 * Size of the packet headers, in bytes, assuming only one message in this packet.
	 */
	public int fullHeadersLengthOneMessage();

	/**
	 * The SocketHandler we are connected to.
	 */
	public SocketHandler getSocketHandler();
	
	/**
	 * The PacketTransportPlugin using this mangler
	 */
	public PacketTransportPlugin getTransport();

	/**
	 * Get our addresses, as peers.
	 */
	public Peer[] getPrimaryIPAddress();

}
