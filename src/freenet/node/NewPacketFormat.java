package freenet.node;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
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
	private static final int NUM_RTTS_TO_LOOSE = 2;
	private static final int NUM_SEQNUMS_TO_WATCH_FOR = 1024;

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
	private final int[] lastRtts;
	private int nextRttPos;

	private final ArrayList<HashMap<Long, MessageWrapper>> startedByPrio;
	private long nextSequenceNumber = 0;
	private long nextMessageID = 0;

	private final HashMap<Long, PartiallyReceivedBuffer> receiveBuffers = new HashMap<Long, PartiallyReceivedBuffer>();
	private final HashMap<Long, SparseBitmap> receiveMaps = new HashMap<Long, SparseBitmap>();

	//FIXME: Should be a better way to store it
	private final HashMap<SessionKey, byte[][]> seqNumWatchLists = new HashMap<SessionKey, byte[][]>();
	private final HashMap<SessionKey, Long> watchListOffsets = new HashMap<SessionKey, Long>();
	private long highestReceivedSeqNum = -1;
	private volatile long highestReceivedAck = -1;

	public NewPacketFormat(PeerNode pn) {
		this.pn = pn;

		startedByPrio = new ArrayList<HashMap<Long, MessageWrapper>>(DMT.NUM_PRIORITIES);
		for(int i = 0; i < DMT.NUM_PRIORITIES; i++) {
			startedByPrio.add(new HashMap<Long, MessageWrapper>());
		}

		lastRtts = new int[100];
		for(int i = 0; i < lastRtts.length; i++) {
			lastRtts[i] = -1;
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
		if(logMINOR) Logger.minor(this, "Received packet " + packet.getSequenceNumber());

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

			if(highestReceivedAck < ack) highestReceivedAck = ack;
		}

		boolean dontAck = false;
		if(packet.getError() || (packet.getFragments().size() == 0)) {
			if(logMINOR) Logger.minor(this, "Not acking because " + (packet.getError() ? "error" : "no fragments"));
			dontAck = true;
		}
		for(MessageFragment fragment : packet.getFragments()) {
			PartiallyReceivedBuffer recvBuffer = receiveBuffers.get(fragment.messageID);
			SparseBitmap recvMap = receiveMaps.get(fragment.messageID);
			if(recvBuffer == null) {
				if(fragment.firstFragment) recvBuffer = new PartiallyReceivedBuffer(fragment.messageLength);
				else recvBuffer = new PartiallyReceivedBuffer();

				recvMap = new SparseBitmap();
				receiveBuffers.put(fragment.messageID, recvBuffer);
				receiveMaps.put(fragment.messageID, recvMap);
			} else {
				if(fragment.firstFragment) recvBuffer.setMessageLength(fragment.messageLength);
			}

			recvBuffer.add(fragment.fragmentData, fragment.fragmentOffset);
			if(fragment.fragmentLength == 0) {
				Logger.warning(this, "Received fragment of length 0");
				continue;
			}
			recvMap.add(fragment.fragmentOffset, fragment.fragmentOffset + fragment.fragmentLength - 1);
			if((recvBuffer.messageLength != -1) && recvMap.contains(0, recvBuffer.messageLength - 1)) {
				receiveBuffers.remove(fragment.messageID);
				receiveMaps.remove(fragment.messageID);
				fullyReceived.add(recvBuffer.buffer);
			}
		}

		if(!dontAck) {
			synchronized(acks) {
				acks.add(packet.getSequenceNumber());
                        }
		}


		return fullyReceived;
	}

	private NPFPacket tryDecipherPacket(byte[] buf, int offset, int length, SessionKey sessionKey) {
		byte[][] seqNumWatchList = seqNumWatchLists.get(sessionKey);
		long watchListOffset = 0;
		if(seqNumWatchList != null) watchListOffset = watchListOffsets.get(sessionKey);

		if(seqNumWatchList == null || (highestReceivedSeqNum > watchListOffset + ((NUM_SEQNUMS_TO_WATCH_FOR * 3) / 4))) {

			seqNumWatchList = new byte[NUM_SEQNUMS_TO_WATCH_FOR][];

			watchListOffset = (highestReceivedSeqNum == -1 ? 0 : highestReceivedSeqNum - (seqNumWatchList.length / 2));
			if(logMINOR) Logger.minor(this, "Recreating watchlist from offset " + watchListOffset);

			long seqNum = watchListOffset;
			for(int i = 0; i < seqNumWatchList.length; i++) {
				byte[] seqNumBytes = new byte[4];
				seqNumBytes[0] = (byte) (seqNum >>> 24);
				seqNumBytes[1] = (byte) (seqNum >>> 16);
				seqNumBytes[2] = (byte) (seqNum >>> 8);
				seqNumBytes[3] = (byte) (seqNum);
				seqNum++;

				PCFBMode cipher = PCFBMode.create(sessionKey.sessionCipher);
				cipher.blockEncipher(seqNumBytes, 0, seqNumBytes.length);

				seqNumWatchList[i] = seqNumBytes;
			}

			seqNumWatchLists.put(sessionKey, seqNumWatchList);
			watchListOffsets.put(sessionKey, watchListOffset);
		}

		boolean hasMatched = false;
		for(int i = 0; (i < seqNumWatchList.length) && !hasMatched; i++) {
			for(int j = 0; j < seqNumWatchList[i].length; j++) {
				if(seqNumWatchList[i][j] != buf[offset + HMAC_LENGTH + j]) break;
				if(j == (seqNumWatchList[i].length - 1)) hasMatched = true;
			}
		}
		if(hasMatched == false) {
			if(logMINOR) Logger.minor(this, "Dropping packet because it isn't on our watchlist");
			return null;
		}

		PCFBMode hashCipher = PCFBMode.create(sessionKey.sessionCipher);
		hashCipher.blockDecipher(buf, offset, HMAC_LENGTH);

		//Check the hash
		MessageDigest md = SHA256.getMessageDigest();
		md.update(buf, offset + HMAC_LENGTH, length - HMAC_LENGTH);
		byte[] hash = md.digest();
		SHA256.returnMessageDigest(md);
		md = null;

		for(int i = 0; i < HMAC_LENGTH; i++) {
			if(buf[offset + i] != hash[i]) {
				return null;
			}
		}

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.sessionCipher);
		payloadCipher.blockDecipher(buf, offset + HMAC_LENGTH, length - HMAC_LENGTH);

		byte[] payload = new byte[length - HMAC_LENGTH];
		System.arraycopy(buf, offset + HMAC_LENGTH, payload, 0, length - HMAC_LENGTH);

		NPFPacket p = NPFPacket.create(payload);
		if(highestReceivedSeqNum < p.getSequenceNumber()) highestReceivedSeqNum = p.getSequenceNumber();

		return p;
	}

	public boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp)
	                throws BlockedTooLongException {
		int maxPacketSize = pn.crypto.socket.getMaxPacketSize();
		NPFPacket packet = createPacket(maxPacketSize - HMAC_LENGTH, pn.getMessageQueue());
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
			synchronized(sentPackets) {
				sentPacket = sentPackets.remove(packet.getSequenceNumber());
			}
			return false;
		}

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.sessionCipher);
		payloadCipher.blockEncipher(data, HMAC_LENGTH, packet.getLength());

		//Add hash
		MessageDigest md = SHA256.getMessageDigest();
		md.update(data, HMAC_LENGTH, packet.getLength());
		byte[] hash = md.digest();
		SHA256.returnMessageDigest(md);
		md = null;

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
			int avgRtt = averageRTT();
			long curTime = System.currentTimeMillis();

			Iterator<Map.Entry<Long, SentPacket>> it = sentPackets.entrySet().iterator();
			while(it.hasNext()) {
				Map.Entry<Long, SentPacket> e = it.next();
				SentPacket s = e.getValue();
				if(s.getSentTime() < (curTime - NUM_RTTS_TO_LOOSE * avgRtt)) {
					if(logMINOR) {
						Logger.minor(this, "Assuming packet " + e.getKey() + " has been lost. "
						                + "Delay " + (curTime - s.getSentTime()) + "ms, "
						                + "threshold " + (NUM_RTTS_TO_LOOSE * avgRtt) + "ms");
					}
					s.lost();
					it.remove();
				}
			}
		}

		SentPacket sentPacket = new SentPacket();
		NPFPacket packet = new NPFPacket();

		long sequenceNumber;
		synchronized(this) {
			sequenceNumber = nextSequenceNumber++;
		}

		if(sequenceNumber > highestReceivedAck + (NUM_SEQNUMS_TO_WATCH_FOR / 2)) {
			//FIXME: Will result in busy looping until we receive a higher ack
			return null;
		}

		packet.setSequenceNumber(sequenceNumber);

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
			HashMap<Long, MessageWrapper> started = startedByPrio.get(i);

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

				long messageID = getMessageID();
				if(messageID == -1) {
					if(logMINOR) Logger.minor(this, "No availiable message ID, requeuing and sending packet");
					messageQueue.pushfrontPrioritizedMessageItem(item);
					break;
				}

				MessageWrapper wrapper = new MessageWrapper(item, messageID);
				MessageFragment frag = wrapper.getMessageFragment(maxPacketSize - packet.getLength());
				if(frag == null) {
					messageQueue.pushfrontPrioritizedMessageItem(item);
					break;
				}
				packet.addMessageFragment(frag);
				sentPacket.addFragment(wrapper, frag.fragmentOffset, frag.fragmentLength);

				//Priority of the one we grabbed might be higher than i
				HashMap<Long, MessageWrapper> queue = startedByPrio.get(item.getPriority());
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

	private long getMessageID() {
		long messageID;
		synchronized(this) {
			messageID = nextMessageID++;
		}
		return messageID;
	}

	private void processFullyReceived(byte[] buf) {
		MessageCore core = pn.node.usm;
		Message m = core.decodeSingleMessage(buf, 0, buf.length, pn, 0);
		if(m != null) {
			core.checkFilters(m, pn.crypto.socket);
		}
	}

	private int averageRTT() {
		int avgRtt = 0;
		int numRtts = 0;
		for(int i = 0; i < lastRtts.length; i++) {
			if(lastRtts[i] < 0) break;
			avgRtt += lastRtts[i];
			++numRtts;
		}

		if(numRtts != 0) avgRtt = avgRtt / numRtts;
		return avgRtt;
	}

	private class SentPacket {
		LinkedList<MessageWrapper> messages = new LinkedList<MessageWrapper>();
		LinkedList<int[]> ranges = new LinkedList<int[]>();
		long sentTime;

		public void addFragment(MessageWrapper source, int start, int length) {
			if(length < 1) throw new IllegalArgumentException();

			messages.add(source);
			ranges.add(new int[] { start, start + length - 1 });
		}

		public long acked() {
			Iterator<MessageWrapper> msgIt = messages.iterator();
			Iterator<int[]> rangeIt = ranges.iterator();

			while(msgIt.hasNext()) {
				MessageWrapper wrapper = msgIt.next();
				int[] range = rangeIt.next();

				if(wrapper.ack(range[0], range[1])) {
					HashMap<Long, MessageWrapper> started = startedByPrio.get(wrapper.getPriority());
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

	private static class PartiallyReceivedBuffer {
		private int messageLength;
		private byte[] buffer;

		private PartiallyReceivedBuffer() {
			messageLength = -1;
			buffer = new byte[0];
		}

		private PartiallyReceivedBuffer(int messageLength) {
			this.messageLength = messageLength;
			buffer = new byte[messageLength];
		}

		private void add(byte[] data, int dataOffset) {
			if(buffer.length < (dataOffset + data.length)) {
				resize(dataOffset + data.length);
			}

			System.arraycopy(data, 0, buffer, dataOffset, data.length);
		}

		private void setMessageLength(int messageLength) {
			this.messageLength = messageLength;
			resize(messageLength);
		}

		private void resize(int length) {
			byte[] newBuffer = new byte[length];
			System.arraycopy(buffer, 0, newBuffer, 0, Math.min(length, buffer.length));
			buffer = newBuffer;
		}
	}
}
