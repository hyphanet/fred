package freenet.node;

import java.util.List;
import java.util.Vector;

import freenet.io.comm.Peer;

public interface PacketFormat {

	boolean handleReceivedPacket(byte[] buf, int offset, int length, long now, Peer replyTo);

	/**
	 * Maybe send something. A SINGLE PACKET. Don't send everything at once, for two reasons:
	 * <ol>
	 * <li>It is possible for a node to have a very long backlog.</li>
	 * <li>Sometimes sending a packet can take a long time.</li>
	 * <li>In the near future PacketSender will be responsible for output bandwidth throttling. So it makes sense to
	 * send a single packet and round-robin.</li>
	 * </ol>
	 * @param ackOnly 
	 */
	boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp, boolean ackOnly)
	                throws BlockedTooLongException;

	/**
	 * Called when the peer has been disconnected.
	 */
	List<MessageItem> onDisconnect();

	/**
	 * Returns {@code false} if the packet format can't send packets because it must wait for some internal event.
	 * For example, if a packet sequence number can not be allocated this method should return {@code false}, but if
	 * nothing can be sent because there is no (external) data to send it should not.
	 * Note that this only applies to packets being created from messages on the @see PeerMessageQueue.
	 * @return {@code false} if the packet format can't send packets
	 */
	boolean canSend();

	/**
	 * @return The time at which the packet format will want to send an ack or similar.
	 * 0 if there are already messages in flight. Long.MAX_VALUE if not supported or if 
	 * there is nothing to ack and nothing in flight. */
	long timeNextUrgent();

	void checkForLostPackets();

	long timeCheckForLostPackets();
}
