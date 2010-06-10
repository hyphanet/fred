package freenet.node;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.support.Logger;

public class NewPacketFormat implements PacketFormat {

	public static final int MIN_MESSAGE_FRAGMENT_SIZE = 20;

	private PeerNode pn;
	private LinkedList<MessageWrapper> started = new LinkedList<MessageWrapper>();
	private LinkedList<Integer> acks = new LinkedList<Integer>();

	public NewPacketFormat(PeerNode pn) {
		this.pn = pn;
	}

	public void handleReceivedPacket(byte[] buf, int offset, int length, long now) {
		throw new UnsupportedOperationException();

	}

	public boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp)
	                throws BlockedTooLongException {
		int maxPacketSize = pn.crypto.socket.getMaxPacketSize();
		byte[] packet = new byte[maxPacketSize];
		int offset = 9; // Sequence number (4), HMAC (4), ACK count (1)

		offset = insertAcks(packet, offset);

		// Try to finish Messages that have been started
		synchronized(started) {
			Iterator<MessageWrapper> it = started.iterator();
			while (it.hasNext() && (offset + MIN_MESSAGE_FRAGMENT_SIZE < maxPacketSize)) {
				MessageWrapper wrapper = it.next();
				offset = insertFragment(packet, offset, maxPacketSize, wrapper);
			}
		}

		// Add messages from message queue
		PeerMessageQueue messageQueue = pn.getMessageQueue();
		while (offset + MIN_MESSAGE_FRAGMENT_SIZE > maxPacketSize) {
			MessageItem item = null;
			synchronized(messageQueue) {
				item = messageQueue.grabQueuedMessageItem();
			}
			if(item == null) break;

			MessageWrapper wrapper = new MessageWrapper(item);

			offset = insertFragment(packet, offset, maxPacketSize, wrapper);
			synchronized(started) {
				started.add(wrapper);
			}
		}

		//TODO: Get Messages from the queue until the packet is big enough, or there are no Messages left
		
		//TODO: Encrypt, add HMAC, add sequence number
		
		try {
	                pn.crypto.socket.sendPacket(packet, pn.getPeer(), pn.allowLocalAddresses());
                } catch (LocalAddressException e) {
	                Logger.error(this, "Caught exception while sending packet", e);
                }

		return false;
	}
	
	private int insertAcks(byte[] packet, int offset) {
		int numAcks = 0;

		// Insert acks
		synchronized(acks) {
			int firstAck = 0;
			Iterator<Integer> it = acks.iterator();
			while (it.hasNext() && numAcks < 256) {
				int ack = it.next();
				if(numAcks == 0) {
					firstAck = ack;

					byte[] data = new byte[4];
					data[0] = (byte) (ack >>> 24);
					data[1] = (byte) (ack >>> 16);
					data[2] = (byte) (ack >>> 8);
					data[3] = (byte) ack;

					System.arraycopy(data, 0, packet, offset, data.length);
					offset += data.length;
				} else {
					// Compress if possible
					int compressedAck = ack - firstAck;
					if((compressedAck < 0) || (compressedAck > 255)) {
						// TODO: If less that 0, we could replace firstAck
						continue;
					}

					packet[offset++] = (byte) (compressedAck);
				}

				++numAcks;
				it.remove();
			}
		}
		
		return offset;
	}

	private int insertFragment(byte[] packet, int offset, int maxPacketSize, MessageWrapper wrapper) {
		// Insert data
		int[] added = wrapper.getData(packet, offset + (wrapper.isLongMessage ? 5 : 2),
		                maxPacketSize - offset);
		
		boolean isFragmented = added[0] != wrapper.data.length;
		boolean firstFragment = added[1] == 0;

		// Add messageID and flags
		int messageID = wrapper.getMessageID();
		messageID = messageID & 0x7FFF; // Make sure the flag bits are 0

		if(wrapper.isLongMessage) messageID = messageID | 0x8000;
		if(isFragmented) messageID = messageID | 0x4000;
		if(firstFragment) messageID = messageID | 0x2000;

		packet[offset++] = (byte) (messageID >>> 8);
		packet[offset++] = (byte) (messageID);

		// Add fragment length, 2 bytes if long message
		if(wrapper.isLongMessage) packet[offset++] = (byte) (added[0] >>> 8);
		packet[offset++] = (byte) (added[0]);

		if(isFragmented) {
			// If firstFragment is true, add total message length. Else, add fragment offset
			int value = firstFragment ? wrapper.data.length : added[1];

			if(wrapper.isLongMessage) {
				packet[offset++] = (byte) (value >>> 16);
				packet[offset++] = (byte) (value >>> 8);
			}
			packet[offset++] = (byte) (value);
		}

		offset += added[0];

		return offset;
	}

	private class MessageWrapper {
		private final byte[] data;
		private final AsyncMessageCallback cb;
		private final ByteCounter ctr;
		private final LinkedList<int[]> acks = new LinkedList<int[]>();
		private final LinkedList<int[]> sent = new LinkedList<int[]>();
		private final boolean isLongMessage;

		private MessageWrapper(MessageItem item) {
			// Use item.cb[0] since MessageItems should't have more than one callback. sendAsync can't add
			// more than one.
			this(item.buf, item.cb[0], item.ctrCallback);
			if(item.cb.length > 1) Logger.error(this, "Got MessageItem with more than one callback");
		}

		private MessageWrapper(byte[] data, AsyncMessageCallback cb, ByteCounter ctr) {
			this.data = data;
			this.cb = cb;
			this.ctr = ctr;
			isLongMessage = data.length > 255;
		}

		/**
		 * Copies up to <code>length</code> bytes of data into an array of bytes. The first byte of data is
		 * stored into element <code>dest[offset]</code>, and the copied bytes are marked as sent.
		 * 
		 * @param dest the destination array
		 * @param offset the first index in <code>dest</code> that is written to
		 * @param length the maximum number of bytes to copy
		 * @return the number of bytes copied into the array at index 0, and the offset of the first copied byte
		 *         at index 1
		 */
		private int[] getData(byte[] dest, int offset, int length) {
			throw new UnsupportedOperationException();
		}

		/**
		 * Copies the bytes between <code>start</code> and <code>end</code> into <code>dest</code>, starting at
		 * <code>offset</code>. Bytes that are copied are marked as sent, unless they have already been marked
		 * as received.
		 * 
		 * @param dest the destination array
		 * @param offset the first index in <code>dest</code> that is written to
		 * @param start the first byte that is copied
		 * @param end the last byte that is copied
		 */
		private void getData(byte[] dest, int offset, int start, int end) {
			throw new UnsupportedOperationException();
		}

		/**
		 * Mark the given range as received.
		 * 
		 * @param start the first byte to be marked
		 * @param end the last byte to be marked
		 */
		private void ack(int start, int end) {
			throw new UnsupportedOperationException();
		}

		/**
		 * Remove any mark that has already been set for the given range.
		 * 
		 * @param start the first byte to be marked
		 * @param end the last byte to be marked
		 */
		private void lost(int start, int end) {
			throw new UnsupportedOperationException();
		}

		private int getMessageID() {
			throw new UnsupportedOperationException();
		}

	}

}
