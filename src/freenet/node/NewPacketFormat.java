package freenet.node;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.support.Logger;
import freenet.support.LogThresholdCallback;
import freenet.support.SparseBitmap;

public class NewPacketFormat implements PacketFormat {

	public static final int MIN_MESSAGE_FRAGMENT_SIZE = 20;
	public static final int HMAC_LENGTH = 4;

	private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}

	private PeerNode pn;
	private HashMap<Integer, MessageWrapper> started = new HashMap<Integer, MessageWrapper>();
	private final LinkedList<Long> acks = new LinkedList<Long>();
	private long nextSequenceNumber = 0;
	private HashMap<Long, SentPacket> sentPackets = new HashMap<Long, SentPacket>();
	private int nextMessageID = 0;

	private HashMap<Integer, byte[]> receiveBuffers = new HashMap<Integer, byte[]>();
	private HashMap<Integer, SparseBitmap> receiveMaps = new HashMap<Integer, SparseBitmap>();

	public NewPacketFormat(PeerNode pn) {
		this.pn = pn;
	}

	public void handleReceivedPacket(byte[] buf, int offset, int length, long now) {
		NPFPacket packet = null;
		SessionKey s = null;
		for(int i = 0; i < 2; i++) {
			if(i == 0) {
				s = pn.getCurrentKeyTracker();
			} else {
				s = pn.getUnverifiedKeyTracker();
			}
			if(s == null) continue;
			packet = tryDecipherPacket(buf, offset, length, s);
			if(packet != null) break;
		}
		if(packet == null) {
			Logger.warning(this, "Could not decrypt received packet");
			return;
		}

		pn.receivedPacket(false, true);
		pn.verified(s);

		if(packet.getAcks().size() > 0) pn.getThrottle().notifyOfPacketAcknowledged();
		for(long ack : packet.getAcks()) {
			synchronized(sentPackets) {
				SentPacket sent = sentPackets.get(ack);
				if(sent != null) {
					sent.acked();
				}
			}
		}

		boolean dontAck = false;
		if(packet.getError() || (packet.getFragments().size() == 0)) {
			Logger.minor(this, "Not acking because " + (packet.getError() ? "error" : "no fragments"));
			dontAck = true;
		}
		for(MessageFragment fragment : packet.getFragments()) {
			byte[] recvBuffer = receiveBuffers.get(fragment.messageID);
			SparseBitmap recvMap = receiveMaps.get(fragment.messageID);
			if(recvBuffer == null) {
				if(!fragment.firstFragment) {
					if(!dontAck) Logger.minor(this, "Not acking because missing first fragment");
					dontAck = true;
					continue;
				}
				recvBuffer = new byte[fragment.messageLength];
				recvMap = new SparseBitmap();
				receiveBuffers.put(fragment.messageID, recvBuffer);
				receiveMaps.put(fragment.messageID, recvMap);
			}

			System.arraycopy(fragment.fragmentData, 0, recvBuffer, fragment.fragmentOffset,
			                fragment.fragmentLength);
			recvMap.add(fragment.fragmentOffset, fragment.fragmentOffset + fragment.fragmentLength - 1);
			if(recvMap.contains(0, recvBuffer.length - 1)) {
				receiveBuffers.remove(fragment.messageID);
				receiveMaps.remove(fragment.messageID);
				processFullyReceived(recvBuffer);
			}
		}

		if(!dontAck) {
			synchronized(acks) {
				acks.add(packet.getSequenceNumber());
                        }
		}
	}

	private NPFPacket tryDecipherPacket(byte[] buf, int offset, int length, SessionKey sessionKey) {
		PCFBMode hashCipher = PCFBMode.create(sessionKey.sessionCipher);
		hashCipher.blockDecipher(buf, offset, HMAC_LENGTH);

		//Check the hash
		MessageDigest md = SHA256.getMessageDigest();
		md.update(buf, offset + HMAC_LENGTH, length - HMAC_LENGTH);
		byte[] hash = md.digest();

		for(int i = 0; i < HMAC_LENGTH; i++) {
			if(buf[offset + i] != hash[i]) {
				return null;
			}
		}

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.sessionCipher);
		payloadCipher.blockDecipher(buf, offset + HMAC_LENGTH, length - HMAC_LENGTH);

		byte[] payload = new byte[length - HMAC_LENGTH];
		System.arraycopy(buf, offset + HMAC_LENGTH, payload, 0, length - HMAC_LENGTH);

		return NPFPacket.create(payload);
	}

	public boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp)
	                throws BlockedTooLongException {
		SentPacket sentPacket = new SentPacket();
		int maxPacketSize = pn.crypto.socket.getMaxPacketSize();
		NPFPacket packet = new NPFPacket();
		packet.setSequenceNumber(nextSequenceNumber++);

		int numAcks = 0;
		synchronized(acks) {
			long firstAck = 0;
			Iterator<Long> it = acks.iterator();
			while (it.hasNext() && numAcks < 256 && packet.getLength() < maxPacketSize) {
				long ack = it.next();
				if(numAcks == 0) {
					firstAck = ack;
				} else {
					// Check that it can be compressed
					long compressedAck = ack - firstAck;
					if((compressedAck < 0) || (compressedAck > 255)) {
						continue;
					}
				}
				packet.addAck(ack);
				++numAcks;
				it.remove();
			}
		}

		//Try to finish messages that have been started
		synchronized(started) {
			Iterator<MessageWrapper> it = started.values().iterator();
			while(it.hasNext() && packet.getLength() < maxPacketSize) {
				MessageWrapper wrapper = it.next();
				MessageFragment frag = wrapper.getMessageFragment(maxPacketSize - packet.getLength());
				if(frag == null) continue;
				packet.addMessageFragment(frag);
				sentPacket.addFragment(wrapper, frag.fragmentOffset, frag.fragmentLength);
				break;
			}
		}

		//Add messages from the message queue
		PeerMessageQueue messageQueue = pn.getMessageQueue();
		while ((packet.getLength() + 10) < maxPacketSize) { //Fragment header is max 9 bytes, allow min 1 byte data
			if(packet.getFragments().size() != 0) break;
			MessageItem item = null;
			synchronized(messageQueue) {
				item = messageQueue.grabQueuedMessageItem();
			}
			if(item == null) break;

			int messageID = getMessageID();
			if(messageID == -1) {
				if(logMINOR) Logger.minor(this, "No availiable message ID, requeuing and sending packet");
				messageQueue.pushfrontPrioritizedMessageItem(item);
				break;
			}

			MessageWrapper wrapper = new MessageWrapper(item, messageID);
			MessageFragment frag = wrapper.getMessageFragment(maxPacketSize - packet.getLength());
			if(frag == null) break;
			packet.addMessageFragment(frag);
			sentPacket.addFragment(wrapper, frag.fragmentOffset, frag.fragmentLength);

			synchronized(started) {
				started.put(messageID, wrapper);
			}
		}

		if(packet.getLength() == 5) return false;

		byte[] data = new byte[packet.getLength() + HMAC_LENGTH];
		packet.toBytes(data, HMAC_LENGTH);

		SessionKey sessionKey = pn.getCurrentKeyTracker();
		if(sessionKey == null) {
			Logger.warning(this, "No key for encrypting hash");
			return false;
		}

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.sessionCipher);
		payloadCipher.blockEncipher(data, HMAC_LENGTH, packet.getLength());

		//Add hash
		MessageDigest md = SHA256.getMessageDigest();
		byte[] hash = md.digest(data);

		PCFBMode hashCipher = PCFBMode.create(sessionKey.sessionCipher);
		hashCipher.blockEncipher(hash, 0, HMAC_LENGTH);

		System.arraycopy(hash, 0, data, 0, HMAC_LENGTH);

		try {
	                pn.crypto.socket.sendPacket(data, pn.getPeer(), pn.allowLocalAddresses());
                } catch (LocalAddressException e) {
	                Logger.error(this, "Caught exception while sending packet", e);
			return false;
                }

		synchronized(sentPackets) {
			sentPackets.put(packet.getSequenceNumber(), sentPacket);
		}
		return true;
	}

	private int getMessageID() {
		int messageID = nextMessageID;

		synchronized(started) {
			if(started.containsKey(messageID)) return -1;
		}

		nextMessageID = (nextMessageID + 1) % 8192;
		return messageID;
	}

	private void processFullyReceived(byte[] buf) {
		MessageCore core = pn.node.usm;
		Message m = core.decodeSingleMessage(buf, 0, buf.length, pn, 0);
		if(m != null) {
			core.checkFilters(m, pn.crypto.socket);
		}
	}

	private class SentPacket {
		LinkedList<MessageWrapper> messages = new LinkedList<MessageWrapper>();
		LinkedList<int[]> ranges = new LinkedList<int[]>();

		public void addFragment(MessageWrapper source, int start, int length) {
			messages.add(source);
			ranges.add(new int[] { start, start + length - 1 });
		}

		public void acked() {
			Iterator<MessageWrapper> msgIt = messages.iterator();
			Iterator<int[]> rangeIt = ranges.iterator();

			while(msgIt.hasNext()) {
				MessageWrapper wrapper = msgIt.next();
				int[] range = rangeIt.next();
				if(wrapper.ack(range[0], range[1])) {
					synchronized(started) {
						started.remove(wrapper.getMessageID());
					}
				}
			}
		}
	}
}
