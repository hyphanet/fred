package freenet.node;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Vector;

import freenet.crypt.BlockCipher;
import freenet.crypt.HMAC;
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
	private static final int MAX_BUFFER_SIZE = 256 * 1024;

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

	private int usedBuffer = 0;
	private int usedBufferOtherSide = 0;
	private final Object bufferUsageLock = new Object();

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
		for(int i = 0; i < 3; i++) {
			if(i == 0) {
				s = pn.getCurrentKeyTracker();
			} else if (i == 1) {
				s = pn.getPreviousKeyTracker();
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
				recvBuffer = new PartiallyReceivedBuffer(this);
				if(fragment.firstFragment) recvBuffer.setMessageLength(fragment.messageLength);

				recvMap = new SparseBitmap();
				receiveBuffers.put(fragment.messageID, recvBuffer);
				receiveMaps.put(fragment.messageID, recvMap);
			} else {
				if(fragment.firstFragment) {
					if(!recvBuffer.setMessageLength(fragment.messageLength)) dontAck = true;
				}
			}

			if(!recvBuffer.add(fragment.fragmentData, fragment.fragmentOffset)) dontAck = true;
			if(fragment.fragmentLength == 0) {
				Logger.warning(this, "Received fragment of length 0");
				continue;
			}
			recvMap.add(fragment.fragmentOffset, fragment.fragmentOffset + fragment.fragmentLength - 1);
			if((recvBuffer.messageLength != -1) && recvMap.contains(0, recvBuffer.messageLength - 1)) {
				receiveBuffers.remove(fragment.messageID);
				receiveMaps.remove(fragment.messageID);
				fullyReceived.add(recvBuffer.buffer);

				synchronized(bufferUsageLock) {
					usedBuffer -= recvBuffer.messageLength;
				}
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

				BlockCipher ivCipher = sessionKey.ivCipher;

				byte[] IV = new byte[ivCipher.getBlockSize() / 8];
				System.arraycopy(seqNumBytes, 0, IV, 0, seqNumBytes.length);

				ivCipher.encipher(IV, IV);

				PCFBMode cipher = PCFBMode.create(sessionKey.sessionCipher, IV);
				cipher.blockEncipher(seqNumBytes, 0, seqNumBytes.length);

				seqNumWatchList[i] = seqNumBytes;
			}

			seqNumWatchLists.put(sessionKey, seqNumWatchList);
			watchListOffsets.put(sessionKey, watchListOffset);
		}

		long sequenceNumber = -1;
		for(int i = 0; (i < seqNumWatchList.length) && (sequenceNumber == -1); i++) {
			for(int j = 0; j < seqNumWatchList[i].length; j++) {
				if(seqNumWatchList[i][j] != buf[offset + HMAC_LENGTH + j]) break;
				if(j == (seqNumWatchList[i].length - 1)) sequenceNumber = watchListOffset + i;
			}
		}
		if(sequenceNumber == -1) {
			if(logMINOR) Logger.minor(this, "Dropping packet because it isn't on our watchlist");
			return null;
		}

		BlockCipher ivCipher = sessionKey.ivCipher;

		byte[] IV = new byte[ivCipher.getBlockSize() / 8];
		IV[0] = (byte) (sequenceNumber >>> 24);
		IV[1] = (byte) (sequenceNumber >>> 16);
		IV[2] = (byte) (sequenceNumber >>> 8);
		IV[3] = (byte) (sequenceNumber);

		ivCipher.encipher(IV, IV);

		byte[] text = new byte[length - HMAC_LENGTH];
		System.arraycopy(buf, offset + HMAC_LENGTH, text, 0, text.length);
		byte[] hash = new byte[HMAC_LENGTH];
		System.arraycopy(buf, offset, hash, 0, hash.length);

		if(!HMAC.verifyWithSHA256(sessionKey.hmacKey, text, hash)) return null;

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.sessionCipher, IV);
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

		BlockCipher ivCipher = sessionKey.ivCipher;

		byte[] IV = new byte[ivCipher.getBlockSize() / 8];
		System.arraycopy(data, HMAC_LENGTH, IV, 0, 4);
		
		ivCipher.encipher(IV, IV);

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.sessionCipher, IV);
		payloadCipher.blockEncipher(data, HMAC_LENGTH, packet.getLength());

		//Add hash
		byte[] text = new byte[packet.getLength()];
		System.arraycopy(data, HMAC_LENGTH, text, 0, text.length);

		byte[] hash = HMAC.macWithSHA256(sessionKey.hmacKey, text, HMAC_LENGTH);

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

		pn.sentPacket();
		pn.reportOutgoingPacket(data, 0, data.length, System.currentTimeMillis());
		if(PeerNode.shouldThrottle(pn.getPeer(), pn.node)) {
			pn.node.outputThrottle.forceGrab(data.length);
		}
		if(packet.getFragments().size() == 0) {
			pn.node.nodeStats.reportNotificationOnlyPacketSent(data.length);
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

		SentPacket sentPacket = new SentPacket(this);
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

				int bufferUsage;
				synchronized(bufferUsageLock) {
					bufferUsage = usedBufferOtherSide;
				}
				if((bufferUsage + item.buf.length) > MAX_BUFFER_SIZE) {
					if(logMINOR) Logger.minor(this, "Would excede remote buffer size, requeuing and sending packet");
					messageQueue.pushfrontPrioritizedMessageItem(item);
					break;
				}

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

				synchronized(bufferUsageLock) {
					usedBufferOtherSide += item.buf.length;
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

	public void onDisconnect() {
		int messageSize = 0;
		for(HashMap<Long, MessageWrapper> queue : startedByPrio) {
			synchronized(queue) {
				for(MessageWrapper wrapper : queue.values()) {
					wrapper.onDisconnect();
					messageSize += wrapper.getLength();
				}
				queue.clear();
			}
		}
		synchronized(bufferUsageLock) {
			usedBufferOtherSide -= messageSize;
		}
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

	private static class SentPacket {
		NewPacketFormat npf;
		LinkedList<MessageWrapper> messages = new LinkedList<MessageWrapper>();
		LinkedList<int[]> ranges = new LinkedList<int[]>();
		long sentTime;

		public SentPacket(NewPacketFormat npf) {
			this.npf = npf;
		}

		public void addFragment(MessageWrapper source, int start, int length) {
			if(length < 1) throw new IllegalArgumentException();

			messages.add(source);
			ranges.add(new int[] { start, start + length - 1 });
		}

		public long acked() {
			Iterator<MessageWrapper> msgIt = messages.iterator();
			Iterator<int[]> rangeIt = ranges.iterator();

			int completedMessagesSize = 0;

			while(msgIt.hasNext()) {
				MessageWrapper wrapper = msgIt.next();
				int[] range = rangeIt.next();

				if(wrapper.ack(range[0], range[1])) {
					HashMap<Long, MessageWrapper> started = npf.startedByPrio.get(wrapper.getPriority());
					synchronized(started) {
						started.remove(wrapper.getMessageID());
					}
					completedMessagesSize = wrapper.getLength();
				}
			}

			synchronized(npf.bufferUsageLock) {
				npf.usedBufferOtherSide -= completedMessagesSize;
			}

			return System.currentTimeMillis() - sentTime;
		}

		public void lost() {
			int bytesToResend = 0;
			Iterator<MessageWrapper> msgIt = messages.iterator();

			while(msgIt.hasNext()) {
				MessageWrapper wrapper = msgIt.next();
				bytesToResend += wrapper.lost();
			}

			//Unless we disconnect these will be resent eventually
			if(npf.pn != null) npf.pn.resendByteCounter.sentBytes(bytesToResend);
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
		private NewPacketFormat npf;

		private PartiallyReceivedBuffer(NewPacketFormat npf) {
			messageLength = -1;
			buffer = new byte[0];
			this.npf = npf;
		}

		private boolean add(byte[] data, int dataOffset) {
			if(buffer.length < (dataOffset + data.length)) {
				if(!resize(dataOffset + data.length)) return false;
			}

			System.arraycopy(data, 0, buffer, dataOffset, data.length);
			return true;
		}

		private boolean setMessageLength(int messageLength) {
			this.messageLength = messageLength;
			return resize(messageLength);
		}

		private boolean resize(int length) {
			synchronized(npf.bufferUsageLock) {
				if((npf.usedBuffer + (length - buffer.length)) > MAX_BUFFER_SIZE) {
					if(logMINOR) Logger.minor(this, "Could not resize buffer, would excede max size");
					return false;
				}

				npf.usedBuffer += (length - buffer.length);
			}

			byte[] newBuffer = new byte[length];
			System.arraycopy(buffer, 0, newBuffer, 0, Math.min(length, buffer.length));
			buffer = newBuffer;

			return true;
		}
	}
}
