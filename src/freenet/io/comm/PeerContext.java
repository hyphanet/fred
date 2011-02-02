/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import java.lang.ref.WeakReference;

import freenet.io.xfer.PacketThrottle;
import freenet.io.xfer.WaitedTooLongException;
import freenet.node.MessageItem;
import freenet.node.OutgoingPacketMangler;
import freenet.node.SyncSendWaitedTooLongException;

/**
 * @author amphibian
 *
 * Everything that is needed to send a message, including the Peer.
 * Implemented by PeerNode, for example.
 */
public interface PeerContext {
	// Largely opaque interface for now
	Peer getPeer();

	/** Force the peer to disconnect.
	 * @param dump If true, the message queue and trackers will be dumped. */
	void forceDisconnect(boolean dump);

	/** Is the peer connected? Have we established the session link? */
	boolean isConnected();

	/** Is the peer connected? are we able to route requests to it? */
	boolean isRoutable();

	/** Peer version, if this is supported, else -1 */
	int getVersionNumber();

	/** Send a message to the node 
	 * @return */
	public MessageItem sendAsync(Message msg, AsyncMessageCallback cb, ByteCounter ctr) throws NotConnectedException;

	/** Send a throttled message to the node (may block for a long time).
	 * @deprecated New packet format throttled everything anyway, so we should get rid of this.
	 * You should call sendAsync or sendSync, and make sure you call sentPayload if appropriate.
	 * Sending asynchronously saves threads and allows unqueueing of messages, preventing
	 * a build up of queued messages, as well as allowing us to get rid of sendThrottledMessage().
	 * @return 
	 * @throws SyncSendWaitedTooLongException
	 * @throws NotConnectedException If the peer is disconnected at the time of sending or becomes so later.
	 * @throws PeerRestartedException If the peer is restarted.
	 * */
	public MessageItem sendThrottledMessage(Message msg, int packetSize, ByteCounter ctr, int timeout, boolean waitForSent, AsyncMessageCallback callback) throws NotConnectedException, WaitedTooLongException, SyncSendWaitedTooLongException, PeerRestartedException;

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

	/** Using old FNP format??? */
	boolean isOldFNP();
}
