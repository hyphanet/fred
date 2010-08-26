package freenet.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Vector;

import freenet.crypt.BlockCipher;
import freenet.crypt.HMAC;
import freenet.crypt.PCFBMode;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.support.Logger;
import freenet.support.LogThresholdCallback;
import freenet.support.SparseBitmap;
import freenet.support.WouldBlockException;

public class NewPacketFormat implements PacketFormat {

	private static final int HMAC_LENGTH = 4;
	private static final int NUM_RTTS_TO_LOOSE = 2;
	private static final int NUM_SEQNUMS_TO_WATCH_FOR = 1024;
	private static final int MAX_BUFFER_SIZE = 256 * 1024;
	private static final int MSG_WINDOW_SIZE = 65536;
	private static final int NUM_MESSAGE_IDS = 268435456;
	private static final long NUM_SEQNUMS = 2147483648l;

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
	private final LinkedList<Integer> acks = new LinkedList<Integer>();
	private final HashMap<Integer, SentPacket> sentPackets = new HashMap<Integer, SentPacket>();
	private final int[] lastRtts;
	private int nextRttPos;

	private int nextSequenceNumber = 0;
	private final ArrayList<HashMap<Integer, MessageWrapper>> startedByPrio;
	private int nextMessageID = 0;
	private int messageWindowPtrAcked = nextMessageID; //The first message id that hasn't been acked by the receiver
	private final SparseBitmap ackedMessages = new SparseBitmap();

	private int highestReceivedSequenceNumber = nextSequenceNumber - 1;
	private final HashMap<Integer, PartiallyReceivedBuffer> receiveBuffers = new HashMap<Integer, PartiallyReceivedBuffer>();
	private final HashMap<Integer, SparseBitmap> receiveMaps = new HashMap<Integer, SparseBitmap>();
	private int messageWindowPtrReceived = nextMessageID; //The first message id that hasn't been fully received
	private final SparseBitmap receivedMessages= new SparseBitmap();

	private SessionKey watchListKey;
	private byte[][] seqNumWatchList;
	private int watchListPointer = 0; //Index of the packet with the lowest sequence number
	private int watchListOffset = nextSequenceNumber; //Sequence number of the packet at seqNumWatchList[watchListPointer]

	private volatile int highestReceivedAck = -1;

	private int usedBuffer = 0;
	private int usedBufferOtherSide = 0;
	private final Object bufferUsageLock = new Object();

	public NewPacketFormat(PeerNode pn) {
		this.pn = pn;

		startedByPrio = new ArrayList<HashMap<Integer, MessageWrapper>>(DMT.NUM_PRIORITIES);
		for(int i = 0; i < DMT.NUM_PRIORITIES; i++) {
			startedByPrio.add(new HashMap<Integer, MessageWrapper>());
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
			if(packet != null) {
				if(logMINOR) Logger.minor(this, "Decrypted packet with tracker " + i);
				break;
			}
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

		for(int ack : packet.getAcks()) {
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
			if(messageWindowPtrReceived + MSG_WINDOW_SIZE > NUM_MESSAGE_IDS) {
				int upperBound = (messageWindowPtrReceived + MSG_WINDOW_SIZE) % NUM_MESSAGE_IDS;
				if((fragment.messageID > upperBound) && (fragment.messageID < messageWindowPtrReceived)) {
					if(logMINOR) Logger.minor(this, "Received message outside window, acking");
					continue;
				}
			} else {
				int upperBound = messageWindowPtrReceived + MSG_WINDOW_SIZE;
				if(!((fragment.messageID >= messageWindowPtrReceived) && (fragment.messageID < upperBound))) {
					if(logMINOR) Logger.minor(this, "Received message outside window, acking");
					continue;
				}
			}
			synchronized(receivedMessages) {
				if(receivedMessages.contains(fragment.messageID, fragment.messageID)) continue;
			}

			PartiallyReceivedBuffer recvBuffer = receiveBuffers.get(fragment.messageID);
			SparseBitmap recvMap = receiveMaps.get(fragment.messageID);
			if(recvBuffer == null) {
				if(logMINOR) Logger.minor(this, "Message id " + fragment.messageID + ": Creating buffer");

				recvBuffer = new PartiallyReceivedBuffer(this);
				if(fragment.firstFragment) {
					if(!recvBuffer.setMessageLength(fragment.messageLength)) {
						dontAck = true;
						continue;
					}
				} else {
					synchronized(bufferUsageLock) {
						if((usedBuffer + fragment.fragmentLength) > MAX_BUFFER_SIZE) {
							if(logMINOR) Logger.minor(this, "Could not create buffer, would excede max size");
							dontAck = true;
							continue;
						}
					}
				}

				recvMap = new SparseBitmap();
				receiveBuffers.put(fragment.messageID, recvBuffer);
				receiveMaps.put(fragment.messageID, recvMap);
			} else {
				if(fragment.firstFragment) {
					if(!recvBuffer.setMessageLength(fragment.messageLength)) {
						dontAck = true;
						continue;
					}
				}
			}

			if(!recvBuffer.add(fragment.fragmentData, fragment.fragmentOffset)) {
				dontAck = true;
				continue;
			}
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
					if(logMINOR) Logger.minor(this, "Removed " + recvBuffer.messageLength + " from buffer. Total is now " + usedBuffer);
				}

				synchronized(receivedMessages) {
					receivedMessages.add(fragment.messageID, fragment.messageID);

					int oldWindow = messageWindowPtrReceived;
					while(receivedMessages.contains(messageWindowPtrReceived, messageWindowPtrReceived)) {
						messageWindowPtrReceived++;
						if(messageWindowPtrReceived == NUM_MESSAGE_IDS) messageWindowPtrReceived = 0;
					}

					if(messageWindowPtrReceived < oldWindow) {
						receivedMessages.remove(oldWindow, NUM_MESSAGE_IDS - 1);
						receivedMessages.remove(0, messageWindowPtrReceived);
					} else {
						receivedMessages.remove(oldWindow, messageWindowPtrReceived);
					}
				}

				if(logMINOR) Logger.minor(this, "Message id " + fragment.messageID + ": Completed");
			} else {
				if(logMINOR) Logger.minor(this, "Message id " + fragment.messageID + ": " + recvMap);
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
		if(watchListKey == null || !watchListKey.equals(sessionKey)) {
			if(logMINOR) Logger.minor(this, "Creating watchlist starting at " + watchListOffset);

			watchListKey = sessionKey;
			seqNumWatchList = new byte[NUM_SEQNUMS_TO_WATCH_FOR][4];
			watchListPointer = 0;

			int seqNum = watchListOffset;
			for(int i = 0; i < seqNumWatchList.length; i++) {
				seqNumWatchList[i] = encryptSequenceNumber(seqNum++, sessionKey);
				if((seqNum == NUM_SEQNUMS) || (seqNum < 0)) seqNum = 0;
			}
		}

		int highestReceivedSeqNum;
		synchronized(this) {
			highestReceivedSeqNum = highestReceivedSequenceNumber;
		}
		int oldHighestReceived = (int) ((0l + watchListOffset + (seqNumWatchList.length / 2)) % NUM_SEQNUMS);
		if(seqNumGreaterThan(highestReceivedSeqNum, oldHighestReceived, 31)) {
			int moveBy;
			if(highestReceivedSeqNum > oldHighestReceived) {
				moveBy = highestReceivedSeqNum - oldHighestReceived;
			} else {
				moveBy = ((int) (NUM_SEQNUMS - oldHighestReceived)) + highestReceivedSeqNum;
			}

			if(moveBy > seqNumWatchList.length) {
				Logger.warning(this, "Moving watchlist pointer by " + moveBy);
			} else if(moveBy < 0) {
				//Tends to happen on startup when not starting at seqnum 0 and highestReceived has been
				//set wrong and then go away, so log at minor and ignore it for now
				if(logMINOR) Logger.minor(this, "Tried moving watchlist pointer by " + moveBy);
				moveBy = 0;
			} else {
				if(logMINOR) Logger.minor(this, "Moving watchlist pointer by " + moveBy);
			}

			int seqNum = (int) ((0l + watchListOffset + seqNumWatchList.length) % NUM_SEQNUMS);
			for(int i = watchListPointer; i < (watchListPointer + moveBy); i++) {
				seqNumWatchList[i % seqNumWatchList.length] = encryptSequenceNumber(seqNum++, sessionKey);
				if(seqNum == NUM_SEQNUMS) seqNum = 0;
			}

			watchListPointer = (watchListPointer + moveBy) % seqNumWatchList.length;
			watchListOffset = (int) ((0l + watchListOffset + moveBy) % NUM_SEQNUMS);
		}

		for(int i = 0; i < seqNumWatchList.length; i++) {
			int index = (watchListPointer + i) % seqNumWatchList.length;
			for(int j = 0; j < seqNumWatchList[index].length; j++) {
				if(seqNumWatchList[index][j] != buf[offset + HMAC_LENGTH + j]) break;
				if(j == (seqNumWatchList[index].length - 1)) {
					NPFPacket p = decipherFromSeqnum(buf, offset, length, sessionKey,
					                (int) ((0l + watchListOffset + i) % NUM_SEQNUMS));
					if(p != null) return p;
				}
			}
		}

		return null;
	}

	private NPFPacket decipherFromSeqnum(byte[] buf, int offset, int length, SessionKey sessionKey, int sequenceNumber) {
		if(sequenceNumber == -1) {
			if(logMINOR) Logger.minor(this, "Dropping packet because it isn't on our watchlist");
			return null;
		} else {
			if(logMINOR) Logger.minor(this, "Received packet matches sequence number " + sequenceNumber);
		}

		BlockCipher ivCipher = sessionKey.ivCipher;

		byte[] IV = new byte[ivCipher.getBlockSize() / 8];
		System.arraycopy(sessionKey.ivNonce, 0, IV, 0, IV.length);
		IV[IV.length - 4] = (byte) (sequenceNumber >>> 24);
		IV[IV.length - 3] = (byte) (sequenceNumber >>> 16);
		IV[IV.length - 2] = (byte) (sequenceNumber >>> 8);
		IV[IV.length - 1] = (byte) (sequenceNumber);

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

		if(seqNumGreaterThan(sequenceNumber, highestReceivedSequenceNumber, 31)) {
			highestReceivedSequenceNumber = sequenceNumber;
		}

		return p;
	}

	private boolean seqNumGreaterThan(long i1, long i2, int serialBits) {
		//halfValue is half the window of possible numbers, so this returns true if the distance from
		//i2->i1 is smaller than i1->i2. See RFC1982 for details and limitations.

		long halfValue = (long) Math.pow(2, serialBits - 1);
		return (((i1 < i2) && ((i2 - i1) > halfValue)) || ((i1 > i2) && (i1 - i2 < halfValue)));
	}

	private byte[] encryptSequenceNumber(int seqNum, SessionKey sessionKey) {
		byte[] seqNumBytes = new byte[4];
		seqNumBytes[0] = (byte) (seqNum >>> 24);
		seqNumBytes[1] = (byte) (seqNum >>> 16);
		seqNumBytes[2] = (byte) (seqNum >>> 8);
		seqNumBytes[3] = (byte) (seqNum);
		seqNum++;
		
		BlockCipher ivCipher = sessionKey.ivCipher;

		byte[] IV = new byte[ivCipher.getBlockSize() / 8];
		System.arraycopy(sessionKey.ivNonce, 0, IV, 0, IV.length);
		System.arraycopy(seqNumBytes, 0, IV, IV.length - seqNumBytes.length, seqNumBytes.length);
		ivCipher.encipher(IV, IV);

		PCFBMode cipher = PCFBMode.create(sessionKey.sessionCipher, IV);
		cipher.blockEncipher(seqNumBytes, 0, seqNumBytes.length);
		
		return seqNumBytes;
	}

	public boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp)
	                throws BlockedTooLongException {
		int maxPacketSize = pn.crypto.socket.getMaxPacketSize();

		SessionKey sessionKey = pn.getCurrentKeyTracker();
		if(sessionKey == null) {
			Logger.warning(this, "No key for encrypting hash");
			return false;
		}

		NPFPacket packet = createPacket(maxPacketSize - HMAC_LENGTH, pn.getMessageQueue());
		if(packet == null) return false;

		packet.setSequenceNumber(getSequenceNumber());

		int paddedLen = packet.getLength() + HMAC_LENGHT;
		if(pn.crypto.config.paddDataPackets()) {
			int packetLength = paddedLen;
			if(logMINOR) Logger.minor(this, "Pre-padding length: " + packetLength);

			if(packetLength < 64) {
				paddedLen = 64 + pn.node.fastWeakRandom.nextInt(32);
			} else {
				paddedLen = ((packetLength + 63) / 64) * 64;
				paddedLen += pn.node.fastWeakRandom.nextInt(64);
				if(packetLength <= maxPacketSize && paddedLen > maxPacketSize)
					paddedLen = maxPacketSize;
			}
		}

		byte[] data = new byte[paddedLen];
		packet.toBytes(data, HMAC_LENGTH, pn.node.fastWeakRandom);

		BlockCipher ivCipher = sessionKey.ivCipher;

		byte[] IV = new byte[ivCipher.getBlockSize() / 8];
		System.arraycopy(sessionKey.ivNonce, 0, IV, 0, IV.length);
		System.arraycopy(data, HMAC_LENGTH, IV, IV.length - 4, 4);
		
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
                } catch (LocalAddressException e) {
	                Logger.error(this, "Caught exception while sending packet", e);
			return false;
                }
		
		if(packet.getFragments().size() != 0) {
			SentPacket sentPacket = new SentPacket(this);
			sentPacket.sent();
			for(MessageFragment frag : packet.getFragments()) {
				sentPacket.addFragment(frag);
			}
			synchronized(sentPackets) {
				sentPackets.put(packet.getSequenceNumber(), sentPacket);
			}
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
			int avgRtt = Math.max(250, averageRTT());
			long curTime = System.currentTimeMillis();

			Iterator<Map.Entry<Integer, SentPacket>> it = sentPackets.entrySet().iterator();
			while(it.hasNext()) {
				Map.Entry<Integer, SentPacket> e = it.next();
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

		NPFPacket packet = new NPFPacket();

		int numAcks = 0;
		synchronized(acks) {
			Iterator<Integer> it = acks.iterator();
			while (it.hasNext() && packet.getLength() < maxPacketSize) {
				if(!packet.addAck(it.next())) break;
				++numAcks;
				it.remove();
			}
		}

fragments:
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
					if(logMINOR) Logger.minor(this, "Would excede remote buffer size, requeuing and sending packet. Remote at " + bufferUsage);
					messageQueue.pushfrontPrioritizedMessageItem(item);
					break fragments;
				}

				int messageID = getMessageID();
				if(messageID == -1) {
					if(logMINOR) Logger.minor(this, "No availiable message ID, requeuing and sending packet");
					messageQueue.pushfrontPrioritizedMessageItem(item);
					break fragments;
				}

				MessageWrapper wrapper = new MessageWrapper(item, messageID);
				MessageFragment frag = wrapper.getMessageFragment(maxPacketSize - packet.getLength());
				if(frag == null) {
					messageQueue.pushfrontPrioritizedMessageItem(item);
					break;
				}
				packet.addMessageFragment(frag);

				//Priority of the one we grabbed might be higher than i
				HashMap<Integer, MessageWrapper> queue = startedByPrio.get(item.getPriority());
				synchronized(queue) {
					queue.put(messageID, wrapper);
				}

				synchronized(bufferUsageLock) {
					usedBufferOtherSide += item.buf.length;
					if(logMINOR) Logger.minor(this, "Added " + item.buf.length + " to remote buffer. Total is now " + usedBufferOtherSide);
				}
			}
		}

		if(packet.getLength() == 5) return null;

		return packet;
	}

	public void onDisconnect() {
		int messageSize = 0;
		for(HashMap<Integer, MessageWrapper> queue : startedByPrio) {
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
			if(logMINOR) Logger.minor(this, "Removed " + messageSize + " from remote buffer. Total is now " + usedBufferOtherSide);
		}
	}

	private synchronized int getSequenceNumber() {
		int seqNum = nextSequenceNumber++;
		if(nextSequenceNumber < 0) nextSequenceNumber = 0;
		return seqNum;
	}

	private int getMessageID() {
		int messageID;
		synchronized(this) {
			if(seqNumGreaterThan(nextMessageID, (messageWindowPtrAcked + MSG_WINDOW_SIZE) % NUM_MESSAGE_IDS, 28)) return -1;
			messageID = nextMessageID++;
			if(nextMessageID == NUM_MESSAGE_IDS) nextMessageID = 0;
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

		if(numRtts == 0) return 250;
		return avgRtt / numRtts;
	}

	private static class SentPacket {
		NewPacketFormat npf;
		LinkedList<MessageWrapper> messages = new LinkedList<MessageWrapper>();
		LinkedList<int[]> ranges = new LinkedList<int[]>();
		long sentTime;

		public SentPacket(NewPacketFormat npf) {
			this.npf = npf;
		}

		public void addFragment(MessageFragment frag) {
			messages.add(frag.wrapper);
			ranges.add(new int[] { frag.fragmentOffset, frag.fragmentOffset + frag.fragmentLength - 1 });
		}

		public long acked() {
			Iterator<MessageWrapper> msgIt = messages.iterator();
			Iterator<int[]> rangeIt = ranges.iterator();

			int completedMessagesSize = 0;

			while(msgIt.hasNext()) {
				MessageWrapper wrapper = msgIt.next();
				int[] range = rangeIt.next();

				if(wrapper.ack(range[0], range[1])) {
					HashMap<Integer, MessageWrapper> started = npf.startedByPrio.get(wrapper.getPriority());
					MessageWrapper removed = null;
					synchronized(started) {
						removed = started.remove(wrapper.getMessageID());
					}

					if(removed != null) {
						completedMessagesSize += wrapper.getLength();

						synchronized(npf.ackedMessages) {
							npf.ackedMessages.add(wrapper.getMessageID(), wrapper.getMessageID());

							int oldWindow = npf.messageWindowPtrAcked;
							while(npf.ackedMessages.contains(npf.messageWindowPtrAcked, npf.messageWindowPtrAcked)) {
								npf.messageWindowPtrAcked++;
								if(npf.messageWindowPtrAcked == NUM_MESSAGE_IDS) npf.messageWindowPtrAcked = 0;
							}

							if(npf.messageWindowPtrAcked < oldWindow) {
								npf.ackedMessages.remove(oldWindow, NUM_MESSAGE_IDS - 1);
								npf.ackedMessages.remove(0, npf.messageWindowPtrAcked);
							} else {
								npf.ackedMessages.remove(oldWindow, npf.messageWindowPtrAcked);
							}
						}
					}
				}
			}

			if(completedMessagesSize > 0) {
				synchronized(npf.bufferUsageLock) {
					npf.usedBufferOtherSide -= completedMessagesSize;
					if(logMINOR) Logger.minor(this, "Removed " + completedMessagesSize + " from remote buffer. Total is now " + npf.usedBufferOtherSide);
				}
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
			if(this.messageLength != -1 && this.messageLength != messageLength) {
				Logger.warning(this, "Message length has already been set to a different length");
			}

			this.messageLength = messageLength;

			if(buffer.length > messageLength) {
				Logger.warning(this, "Buffer is larger than set message length! (" + buffer.length + ">" + messageLength + ")");
			}

			return resize(messageLength);
		}

		private boolean resize(int length) {
			if(logMINOR) Logger.minor(this, "Resizing from " + buffer.length + " to " + length);

			synchronized(npf.bufferUsageLock) {
				if((npf.usedBuffer + (length - buffer.length)) > MAX_BUFFER_SIZE) {
					if(logMINOR) Logger.minor(this, "Could not resize buffer, would excede max size");
					return false;
				}

				npf.usedBuffer += (length - buffer.length);
				if(logMINOR) Logger.minor(this, "Added " + (length - buffer.length) + " to buffer. Total is now " + npf.usedBuffer);
			}

			byte[] newBuffer = new byte[length];
			System.arraycopy(buffer, 0, newBuffer, 0, Math.min(length, buffer.length));
			buffer = newBuffer;

			return true;
		}
	}
}
