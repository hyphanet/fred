package freenet.node;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

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
		// TODO: Decrypt
		// TODO: Check HMAC
		// TODO: Ack packet sequence number
		// TODO: Go through the acks
		// TODO: Handle received message fragments
		throw new UnsupportedOperationException();

	}

	public boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp)
	                throws BlockedTooLongException {
		// TODO: Record what was sent in this packet

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

		byte[] data = new byte[offset];
		System.arraycopy(packet, 0, data, 0, data.length);

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
		int[] added = wrapper.getData(packet, offset + (wrapper.isLongMessage() ? 5 : 2),
		                maxPacketSize - offset);
		
		boolean isFragmented = added[0] != wrapper.getLength();
		boolean firstFragment = added[1] == 0;

		// Add messageID and flags
		int messageID = wrapper.getMessageID();
		messageID = messageID & 0x7FFF; // Make sure the flag bits are 0

		if(wrapper.isLongMessage()) messageID = messageID | 0x8000;
		if(isFragmented) messageID = messageID | 0x4000;
		if(firstFragment) messageID = messageID | 0x2000;

		packet[offset++] = (byte) (messageID >>> 8);
		packet[offset++] = (byte) (messageID);

		// Add fragment length, 2 bytes if long message
		if(wrapper.isLongMessage()) packet[offset++] = (byte) (added[0] >>> 8);
		packet[offset++] = (byte) (added[0]);

		if(isFragmented) {
			// If firstFragment is true, add total message length. Else, add fragment offset
			int value = firstFragment ? wrapper.getLength() : added[1];

			if(wrapper.isLongMessage()) {
				packet[offset++] = (byte) (value >>> 16);
				packet[offset++] = (byte) (value >>> 8);
			}
			packet[offset++] = (byte) (value);
		}

		offset += added[0];

		return offset;
	}

}
