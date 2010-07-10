package freenet.node;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.support.Logger;
import freenet.support.LogThresholdCallback;
import freenet.support.SparseBitmap;

public class NewPacketFormat implements PacketFormat {

	private static final int HMAC_LENGTH = 4;
	private static final int PACKET_WINDOW = 128; //TODO: Find a good value
	private static final int NUM_RTTS_TO_LOOSE = 2;
	private static final int NUM_RTTS_MSGID_WAIT = 10;
	private static final int NUM_MESSAGE_IDS = 8192;

	private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}

	private final PeerNode pn;
	private final LinkedList<Long> acks = new LinkedList<Long>();
	private final HashMap<Long, SentPacket> sentPackets = new HashMap<Long, SentPacket>();
	private final int[] lastRtts = new int[100];
	private int nextRttPos;

	private final ArrayList<HashMap<Integer, MessageWrapper>> startedByPrio;
	private long nextSequenceNumber = 0;
	private int nextMessageID = 0;
	private final HashMap<Integer, Long> msgIDCloseTimeSent = new HashMap<Integer, Long>();

	private final HashMap<Integer, byte[]> receiveBuffers = new HashMap<Integer, byte[]>();
	private final HashMap<Integer, SparseBitmap> receiveMaps = new HashMap<Integer, SparseBitmap>();
	private long highestAckedSeqNum = -1;
	private final HashMap<Integer, Long> msgIDCloseTimeRecv = new HashMap<Integer, Long>();

	public NewPacketFormat(PeerNode pn) {
		this.pn = pn;

		startedByPrio = new ArrayList<HashMap<Integer, MessageWrapper>>(DMT.NUM_PRIORITIES);
		for(int i = 0; i < DMT.NUM_PRIORITIES; i++) {
			startedByPrio.add(new HashMap<Integer, MessageWrapper>());
		}
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

		if(packet.getSequenceNumber() < (highestAckedSeqNum - PACKET_WINDOW)) {
			if(logMINOR) Logger.minor(this, "Dropping late packet");
			return;
		}

		pn.receivedPacket(false, true);
		pn.verified(s);

		if(packet.getAcks().size() > 0) pn.getThrottle().notifyOfPacketAcknowledged();
		
		LinkedList<byte[]> finished = handleDecryptedPacket(packet);
		for(byte[] buffer : finished) {
			processFullyReceived(buffer);
		}
	}

	LinkedList<byte[]> handleDecryptedPacket(NPFPacket packet) {
		LinkedList<byte[]> fullyReceived = new LinkedList<byte[]>();

		for(long ack : packet.getAcks()) {
			synchronized(sentPackets) {
				SentPacket sent = sentPackets.remove(ack);
				if(sent != null) {
					long rtt = sent.acked();
					lastRtts[nextRttPos] = (int) (Math.min(rtt, Integer.MAX_VALUE));
					nextRttPos = (nextRttPos + 1) % lastRtts.length;
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
				Long time = msgIDCloseTimeRecv.get(fragment.messageID);
				if(time != null) {
					if(time < (System.currentTimeMillis() - NUM_RTTS_MSGID_WAIT * maxRTT())) {
						if(logMINOR) Logger.minor(this, "Ignoring fragment because we finished "
						                + "the message fragment recently");
						continue;
					} else {
						msgIDCloseTimeRecv.remove(fragment.messageID);
					}
				}
				
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
			if(fragment.fragmentLength == 0) {
				Logger.warning(this, "Received fragment of length 0");
				continue;
			}
			recvMap.add(fragment.fragmentOffset, fragment.fragmentOffset + fragment.fragmentLength - 1);
			if(recvMap.contains(0, recvBuffer.length - 1)) {
				receiveBuffers.remove(fragment.messageID);
				receiveMaps.remove(fragment.messageID);
				msgIDCloseTimeRecv.put(fragment.messageID, System.currentTimeMillis());
				fullyReceived.add(recvBuffer);
			}
		}

		if(!dontAck) {
			synchronized(acks) {
				acks.add(packet.getSequenceNumber());
                        }
			highestAckedSeqNum = packet.getSequenceNumber();
		}


		return fullyReceived;
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
		int maxPacketSize = pn.crypto.socket.getMaxPacketSize();
		NPFPacket packet = createPacket(maxPacketSize, pn.getMessageQueue());
		if(packet == null) return false;

		//TODO: Do this properly
		SentPacket sentPacket;
		synchronized(sentPackets) {
			sentPacket = sentPackets.get(packet.getSequenceNumber());
		}

		byte[] data = new byte[packet.getLength() + HMAC_LENGTH];
		packet.toBytes(data, HMAC_LENGTH);

		SessionKey sessionKey = pn.getCurrentKeyTracker();
		if(sessionKey == null) {
			Logger.warning(this, "No key for encrypting hash");
			if(sentPacket != null) sentPacket.lost();
			return false;
		}

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.sessionCipher);
		payloadCipher.blockEncipher(data, HMAC_LENGTH, packet.getLength());

		//Add hash
		MessageDigest md = SHA256.getMessageDigest();
		md.update(data, HMAC_LENGTH, packet.getLength());
		byte[] hash = md.digest();

		PCFBMode hashCipher = PCFBMode.create(sessionKey.sessionCipher);
		hashCipher.blockEncipher(hash, 0, HMAC_LENGTH);

		System.arraycopy(hash, 0, data, 0, HMAC_LENGTH);

		try {
			if(logMINOR) {
				String fragments = null;
				for(MessageFragment frag : packet.getFragments()) {
					if(fragments == null) fragments = "" + frag.messageID;
					else fragments = fragments + ", " + frag.messageID;
				}

				Logger.minor(this, "Sending packet " + packet.getSequenceNumber() + " ("
				                + data.length + " bytes) with fragments " + fragments + " and "
				                + packet.getAcks().size() + " acks");
			}
	                pn.crypto.socket.sendPacket(data, pn.getPeer(), pn.allowLocalAddresses());
			if(sentPacket != null) sentPacket.sent();
                } catch (LocalAddressException e) {
	                Logger.error(this, "Caught exception while sending packet", e);
			if(sentPacket != null) sentPacket.lost();
			synchronized(sentPackets) {
				sentPackets.remove(packet.getSequenceNumber());
			}
			return false;
                }
		return true;
	}

	NPFPacket createPacket(int maxPacketSize, PeerMessageQueue messageQueue) {
		//Mark packets as lost
		synchronized(sentPackets) {
			int maxRtt = maxRTT();
			long curTime = System.currentTimeMillis();

			Iterator<Long> it = sentPackets.keySet().iterator();
			while(it.hasNext()) {
				Long l = it.next();
				SentPacket s = sentPackets.get(l);
				if(s.getSentTime() < (curTime - NUM_RTTS_TO_LOOSE * maxRtt)) {
					if(logMINOR) {
						Logger.minor(this, "Assuming packet " + l + " has been lost. "
						                + "Delay " + (curTime - s.getSentTime()) + "ms, "
						                + "threshold " + (NUM_RTTS_TO_LOOSE * maxRtt) + "ms");
					}
					s.lost();
					it.remove();
				}
			}
		}

		SentPacket sentPacket = new SentPacket();
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

		for(int i = 0; i < startedByPrio.size(); i++) {
			HashMap<Integer, MessageWrapper> started = startedByPrio.get(i);

			//Try to finish messages that have been started
			synchronized(started) {
				Iterator<MessageWrapper> it = started.values().iterator();
				while(it.hasNext() && packet.getLength() < maxPacketSize) {
					MessageWrapper wrapper = it.next();
					MessageFragment frag = wrapper.getMessageFragment(maxPacketSize - packet.getLength());
					if(frag == null) continue;
					packet.addMessageFragment(frag);
					sentPacket.addFragment(wrapper, frag.fragmentOffset, frag.fragmentLength);
				}
			}

			//Add messages from the message queue
			while ((packet.getLength() + 10) < maxPacketSize) { //Fragment header is max 9 bytes, allow min 1 byte data
				MessageItem item = null;
				synchronized(messageQueue) {
					item = messageQueue.grabQueuedMessageItem(i);
				}
				if(item == null) break;

				int messageID = getMessageID();
				if(messageID == -1) {
					Logger.warning(this, "No availiable message ID, requeuing and sending packet");
					messageQueue.pushfrontPrioritizedMessageItem(item);
					break;
				}

				MessageWrapper wrapper = new MessageWrapper(item, messageID);
				MessageFragment frag = wrapper.getMessageFragment(maxPacketSize - packet.getLength());
				if(frag == null) break;
				packet.addMessageFragment(frag);
				sentPacket.addFragment(wrapper, frag.fragmentOffset, frag.fragmentLength);

				//Priority of the one we grabbed might be higher than i
				HashMap<Integer, MessageWrapper> queue = startedByPrio.get(item.getPriority());
				synchronized(queue) {
					queue.put(messageID, wrapper);
				}
			}
		}

		if(packet.getLength() == 5) return null;

		if(packet.getFragments().size() != 0) {
			synchronized(sentPackets) {
				sentPackets.put(packet.getSequenceNumber(), sentPacket);
			}
		}

		sentPacket.sent();
		
		return packet;
	}

	private int getMessageID() {
		int messageID = nextMessageID;

		synchronized(msgIDCloseTimeSent) {
			Long time = msgIDCloseTimeSent.get(messageID);
			if(time != null) {
				if(time > (System.currentTimeMillis() - NUM_RTTS_MSGID_WAIT * maxRTT())) {
					return -1;
				} else {
					msgIDCloseTimeSent.remove(messageID);
				}
			}
		}

		for(HashMap<Integer, MessageWrapper> started : startedByPrio) {
			synchronized(started) {
				if(started.containsKey(messageID)) return -1;
			}
		}

		nextMessageID = (nextMessageID + 1) % NUM_MESSAGE_IDS;
		return messageID;
	}

	private void processFullyReceived(byte[] buf) {
		MessageCore core = pn.node.usm;
		Message m = core.decodeSingleMessage(buf, 0, buf.length, pn, 0);
		if(m != null) {
			core.checkFilters(m, pn.crypto.socket);
		}
	}

	private int maxRTT() {
		int maxRtt = 0;
		for(int rtt : lastRtts) {
			maxRtt = Math.max(rtt, maxRtt);
		}
		return maxRtt;
	}

	private class SentPacket {
		LinkedList<MessageWrapper> messages = new LinkedList<MessageWrapper>();
		LinkedList<int[]> ranges = new LinkedList<int[]>();
		long sentTime;

		public void addFragment(MessageWrapper source, int start, int length) {
			messages.add(source);
			ranges.add(new int[] { start, start + length - 1 });
		}

		public long acked() {
			Iterator<MessageWrapper> msgIt = messages.iterator();
			Iterator<int[]> rangeIt = ranges.iterator();

			while(msgIt.hasNext()) {
				MessageWrapper wrapper = msgIt.next();
				int[] range = rangeIt.next();

				if(range[1] - range[0] < 0) {
					Logger.minor(this, "Would ack negative range");
					continue;
				}

				if(wrapper.ack(range[0], range[1])) {
					synchronized(msgIDCloseTimeSent) {
						msgIDCloseTimeSent.put(wrapper.getMessageID(), System.currentTimeMillis());
					}
					HashMap<Integer, MessageWrapper> started = startedByPrio.get(wrapper.getPriority());
					synchronized(started) {
						started.remove(wrapper.getMessageID());
					}
				}
			}

			return System.currentTimeMillis() - sentTime;
		}

		public void lost() {
			Iterator<MessageWrapper> msgIt = messages.iterator();

			while(msgIt.hasNext()) {
				MessageWrapper wrapper = msgIt.next();
				wrapper.lost();
			}
		}

		public void sent() {
			sentTime = System.currentTimeMillis();
		}

		public long getSentTime() {
			return sentTime;
		}
	}
}
