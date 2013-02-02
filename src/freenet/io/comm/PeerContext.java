/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import java.lang.ref.WeakReference;

import freenet.io.xfer.PacketThrottle;
import freenet.node.MessageItem;
import freenet.node.OutgoingPacketMangler;

/**
 * @author amphibian
 *
 * Everything that is needed to send a message, including the Peer.
 * Implemented by PeerNode, for example.
 */
public interface PeerContext {
	// Largely opaque interface for now
	Peer getPeer();

	/** Force the peer to disconnect. */
	void forceDisconnect();

	/** Is the peer connected? Have we established the session link? */
	boolean isConnected();

	/** Is the peer connected? are we able to route requests to it? */
	boolean isRoutable();

	/** Peer version, if this is supported, else -1 */
	int getVersionNumber();

	/** Send a message to the node 
	 * @return */
	public MessageItem sendAsync(Message msg, AsyncMessageCallback cb, ByteCounter ctr) throws NotConnectedException;

	/** Get the current boot ID. This is a random number that changes every time the node starts up. */
	public long getBootID();

	/** Get the PacketThrottle for the node's current address for the standard packet size (if the
	 * address changes then we get a new throttle). */
	public PacketThrottle getThrottle();

	/** Get the SocketHandler which handles incoming packets from this node */
	SocketHandler getSocketHandler();

	/** Get the OutgoingPacketMangler which encrypts outgoing packets to this node */
	OutgoingPacketMangler getOutgoingMangler();

	/** Get a WeakReference to this context. Hopefully there is only one of these for the whole object; they are quite
	 * expensive. */
	WeakReference<? extends PeerContext> getWeakRef();

	/** Compact toString() */
	String shortToString();

	/** Report a transfer failure */
	void transferFailed(String reason, boolean realTime);

	boolean unqueueMessage(MessageItem item);

	void reportThrottledPacketSendTime(long time, boolean realTime);

	int getThrottleWindowSize();
}
