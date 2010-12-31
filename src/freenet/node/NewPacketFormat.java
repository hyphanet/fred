/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

import freenet.crypt.BlockCipher;
import freenet.crypt.HMAC;
import freenet.crypt.PCFBMode;
import freenet.io.comm.DMT;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.node.NewPacketFormatKeyContext.AddedAcks;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SparseBitmap;

public class NewPacketFormat implements PacketFormat {

	private static final int HMAC_LENGTH = 4;
	private static final int NUM_SEQNUMS_TO_WATCH_FOR = 1024;
	private static final int MAX_BUFFER_SIZE = 256 * 1024;
	private static final int MSG_WINDOW_SIZE = 65536;
	private static final int NUM_MESSAGE_IDS = 268435456;
	static final long NUM_SEQNUMS = 2147483648l;
	private static final int MAX_MSGID_BLOCK_TIME = 10 * 60 * 1000;
	private static final int MAX_ACKS = 500;

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	private final BasePeerNode pn;

	private final ArrayList<HashMap<Integer, MessageWrapper>> startedByPrio;
	private int nextMessageID;
	/** The first message id that hasn't been acked by the receiver */
	private int messageWindowPtrAcked;
	private final SparseBitmap ackedMessages = new SparseBitmap();

	private final HashMap<Integer, PartiallyReceivedBuffer> receiveBuffers = new HashMap<Integer, PartiallyReceivedBuffer>();
	private final HashMap<Integer, SparseBitmap> receiveMaps = new HashMap<Integer, SparseBitmap>();
	/** The first message id that hasn't been fully received */
	private int messageWindowPtrReceived;
	private final SparseBitmap receivedMessages= new SparseBitmap();

	private int usedBuffer = 0;
	private int usedBufferOtherSide = 0;
	private final Object bufferUsageLock = new Object();

	public NewPacketFormat(BasePeerNode pn, int ourInitialMsgID, int theirInitialMsgID) {
		this.pn = pn;

		startedByPrio = new ArrayList<HashMap<Integer, MessageWrapper>>(DMT.NUM_PRIORITIES);
		for(int i = 0; i < DMT.NUM_PRIORITIES; i++) {
			startedByPrio.add(new HashMap<Integer, MessageWrapper>());
		}

		// Make sure the numbers are within the ranges we want
		ourInitialMsgID = (ourInitialMsgID & 0x7FFFFFFF) % NUM_MESSAGE_IDS;
		theirInitialMsgID = (theirInitialMsgID & 0x7FFFFFFF) % NUM_MESSAGE_IDS;

		nextMessageID = ourInitialMsgID;
		messageWindowPtrAcked = ourInitialMsgID;
		messageWindowPtrReceived = theirInitialMsgID;
	}

	public boolean handleReceivedPacket(byte[] buf, int offset, int length, long now) {
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
				if(logDEBUG) Logger.debug(this, "Decrypted packet with tracker " + i);
				break;
			}
		}
		if(packet == null) {
			Logger.warning(this, "Could not decrypt received packet");
			return false;
		}
		if(logMINOR) Logger.minor(this, "Received packet " + packet.getSequenceNumber());

		pn.receivedPacket(false, true);
		pn.verified(s);
		pn.maybeRekey();
		pn.reportIncomingPacket(buf, offset, length, now);

		LinkedList<byte[]> finished = handleDecryptedPacket(packet, s);
		for(byte[] buffer : finished) {
			pn.processDecryptedMessage(buffer, 0, buffer.length, 0);
		}

		return true;
	}

	LinkedList<byte[]> handleDecryptedPacket(NPFPacket packet, SessionKey sessionKey) {
		LinkedList<byte[]> fullyReceived = new LinkedList<byte[]>();

		NewPacketFormatKeyContext keyContext = (NewPacketFormatKeyContext) sessionKey.packetContext;
		for(int ack : packet.getAcks()) {
			keyContext.ack(ack, pn);
		}
		
		boolean dontAck = false;
		boolean wakeUp = false;
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
					if(logDEBUG) Logger.debug(this, "Removed " + recvBuffer.messageLength + " from buffer. Total is now " + usedBuffer);
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
				if(logDEBUG) Logger.debug(this, "Message id " + fragment.messageID + ": " + recvMap);
			}
		}

		if(!dontAck) {
			int seqno = packet.getSequenceNumber();
			int acksQueued = keyContext.queueAck(seqno);
			boolean addedAck = acksQueued >= 0;
			if(acksQueued > MAX_ACKS)
				wakeUp = true;
			if(addedAck) {
				if(!wakeUp) {
					synchronized(bufferUsageLock) {
						if(usedBuffer > MAX_BUFFER_SIZE / 2)
							wakeUp = true;
					}
				}
				if(wakeUp)
					pn.wakeUpSender();
			}
		}


		return fullyReceived;
	}

	private NPFPacket tryDecipherPacket(byte[] buf, int offset, int length, SessionKey sessionKey) {
		NewPacketFormatKeyContext keyContext = (NewPacketFormatKeyContext) sessionKey.packetContext;
		// Create the watchlist if the key has changed
		if(keyContext.seqNumWatchList == null) {
			if(logMINOR) Logger.minor(this, "Creating watchlist starting at " + keyContext.watchListOffset);
			
			keyContext.seqNumWatchList = new byte[NUM_SEQNUMS_TO_WATCH_FOR][4];

			int seqNum = keyContext.watchListOffset;
			for(int i = 0; i < keyContext.seqNumWatchList.length; i++) {
				keyContext.seqNumWatchList[i] = encryptSequenceNumber(seqNum++, sessionKey);
				if((seqNum == NUM_SEQNUMS) || (seqNum < 0)) seqNum = 0;
			}
		}

		// Move the watchlist if needed
		int highestReceivedSeqNum;
		synchronized(this) {
			highestReceivedSeqNum = keyContext.highestReceivedSeqNum;
		}
		// The entry for the highest received sequence number is kept in the middle of the list
		int oldHighestReceived = (int) ((0l + keyContext.watchListOffset + (keyContext.seqNumWatchList.length / 2)) % NUM_SEQNUMS);
		if(seqNumGreaterThan(highestReceivedSeqNum, oldHighestReceived, 31)) {
			int moveBy;
			if(highestReceivedSeqNum > oldHighestReceived) {
				moveBy = highestReceivedSeqNum - oldHighestReceived;
			} else {
				moveBy = ((int) (NUM_SEQNUMS - oldHighestReceived)) + highestReceivedSeqNum;
			}

			if(moveBy > keyContext.seqNumWatchList.length) {
				Logger.warning(this, "Moving watchlist pointer by " + moveBy);
			} else if(moveBy < 0) {
				Logger.warning(this, "Tried moving watchlist pointer by " + moveBy);
				moveBy = 0;
			} else {
				if(logDEBUG) Logger.debug(this, "Moving watchlist pointer by " + moveBy);
			}

			int seqNum = (int) ((0l + keyContext.watchListOffset + keyContext.seqNumWatchList.length) % NUM_SEQNUMS);
			for(int i = keyContext.watchListPointer; i < (keyContext.watchListPointer + moveBy); i++) {
				keyContext.seqNumWatchList[i % keyContext.seqNumWatchList.length] = encryptSequenceNumber(seqNum++, sessionKey);
				if(seqNum == NUM_SEQNUMS) seqNum = 0;
			}

			keyContext.watchListPointer = (keyContext.watchListPointer + moveBy) % keyContext.seqNumWatchList.length;
			keyContext.watchListOffset = (int) ((0l + keyContext.watchListOffset + moveBy) % NUM_SEQNUMS);
		}

outer:
		for(int i = 0; i < keyContext.seqNumWatchList.length; i++) {
			int index = (keyContext.watchListPointer + i) % keyContext.seqNumWatchList.length;
			for(int j = 0; j < keyContext.seqNumWatchList[index].length; j++) {
				if(keyContext.seqNumWatchList[index][j] != buf[offset + HMAC_LENGTH + j]) continue outer;
			}
			
			int sequenceNumber = (int) ((0l + keyContext.watchListOffset + i) % NUM_SEQNUMS);
			if(logDEBUG) Logger.debug(this, "Received packet matches sequence number " + sequenceNumber);
			NPFPacket p = decipherFromSeqnum(buf, offset, length, sessionKey, sequenceNumber);
			if(p != null) return p;
		}

		return null;
	}

	private NPFPacket decipherFromSeqnum(byte[] buf, int offset, int length, SessionKey sessionKey, int sequenceNumber) {
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

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.incommingCipher, IV);
		payloadCipher.blockDecipher(buf, offset + HMAC_LENGTH, length - HMAC_LENGTH);

		byte[] payload = new byte[length - HMAC_LENGTH];
		System.arraycopy(buf, offset + HMAC_LENGTH, payload, 0, length - HMAC_LENGTH);

		NPFPacket p = NPFPacket.create(payload);

		NewPacketFormatKeyContext keyContext = (NewPacketFormatKeyContext) sessionKey.packetContext;
		synchronized(this) {
			if(seqNumGreaterThan(sequenceNumber, keyContext.highestReceivedSeqNum, 31)) {
				keyContext.highestReceivedSeqNum = sequenceNumber;
			}
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

		BlockCipher ivCipher = sessionKey.ivCipher;

		byte[] IV = new byte[ivCipher.getBlockSize() / 8];
		System.arraycopy(sessionKey.ivNonce, 0, IV, 0, IV.length);
		System.arraycopy(seqNumBytes, 0, IV, IV.length - seqNumBytes.length, seqNumBytes.length);
		ivCipher.encipher(IV, IV);

		PCFBMode cipher = PCFBMode.create(sessionKey.incommingCipher, IV);
		cipher.blockEncipher(seqNumBytes, 0, seqNumBytes.length);

		return seqNumBytes;
	}

	public boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp, boolean ackOnly)
	throws BlockedTooLongException {
		SessionKey sessionKey = pn.getPreviousKeyTracker();
		if(sessionKey != null) {
			// Try to sent an ack-only packet.
			if(maybeSendPacket(now, rpiTemp, rpiIntTemp, true, sessionKey)) return true;
		}
		sessionKey = pn.getUnverifiedKeyTracker();
		if(sessionKey != null) {
			// Try to sent an ack-only packet.
			if(maybeSendPacket(now, rpiTemp, rpiIntTemp, true, sessionKey)) return true;
		}
		sessionKey = pn.getCurrentKeyTracker();
		if(sessionKey == null) {
			Logger.warning(this, "No key for encrypting hash");
			return false;
		}
		return maybeSendPacket(now, rpiTemp, rpiIntTemp, ackOnly, sessionKey);
	}
	
	public boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp, boolean ackOnly, SessionKey sessionKey)
	throws BlockedTooLongException {
		int maxPacketSize = pn.getMaxPacketSize();
		NewPacketFormatKeyContext keyContext = (NewPacketFormatKeyContext) sessionKey.packetContext;

		NPFPacket packet = createPacket(maxPacketSize - HMAC_LENGTH, pn.getMessageQueue(), sessionKey, ackOnly);
		if(packet == null) return false;

		int paddedLen = packet.getLength() + HMAC_LENGTH;
		if(pn.shouldPadDataPackets()) {
			int packetLength = paddedLen;
			if(logDEBUG) Logger.debug(this, "Pre-padding length: " + packetLength);

			if(packetLength < 64) {
				paddedLen = 64 + pn.paddingGen().nextInt(32);
			} else {
				paddedLen = ((packetLength + 63) / 64) * 64;
				if(paddedLen < maxPacketSize) {
					paddedLen += pn.paddingGen().nextInt(Math.min(64, maxPacketSize - paddedLen));
				} else if((packetLength <= maxPacketSize) && (paddedLen > maxPacketSize)) {
					paddedLen = maxPacketSize;
				}
			}
		}

		byte[] data = new byte[paddedLen];
		packet.toBytes(data, HMAC_LENGTH, pn.paddingGen());

		BlockCipher ivCipher = sessionKey.ivCipher;

		byte[] IV = new byte[ivCipher.getBlockSize() / 8];
		System.arraycopy(sessionKey.ivNonce, 0, IV, 0, IV.length);
		System.arraycopy(data, HMAC_LENGTH, IV, IV.length - 4, 4);

		ivCipher.encipher(IV, IV);

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.outgoingCipher, IV);
		payloadCipher.blockEncipher(data, HMAC_LENGTH, paddedLen - HMAC_LENGTH);

		//Add hash
		byte[] text = new byte[paddedLen - HMAC_LENGTH];
		System.arraycopy(data, HMAC_LENGTH, text, 0, text.length);

		byte[] hash = HMAC.macWithSHA256(sessionKey.hmacKey, text, HMAC_LENGTH);

		System.arraycopy(hash, 0, data, 0, HMAC_LENGTH);

		try {
			if(logMINOR) {
				String fragments = null;
				for(MessageFragment frag : packet.getFragments()) {
					if(fragments == null) fragments = "" + frag.messageID;
					else fragments = fragments + ", " + frag.messageID;
					fragments += " ("+frag.fragmentOffset+"->"+(frag.fragmentOffset+frag.fragmentLength-1)+")";
				}

				Logger.minor(this, "Sending packet " + packet.getSequenceNumber() + " ("
				                + data.length + " bytes) with fragments " + fragments + " and "
				                + packet.getAcks().size() + " acks");
			}
			pn.sendEncryptedPacket(data);
		} catch (LocalAddressException e) {
			Logger.error(this, "Caught exception while sending packet", e);
			return false;
		}
		
		packet.onSent(data.length);

		if(packet.getFragments().size() > 0) {
			keyContext.sent(packet.getSequenceNumber(), packet.getLength());
		}

		pn.sentPacket();
		pn.reportOutgoingPacket(data, 0, data.length, System.currentTimeMillis());
		if(pn.shouldThrottle()) {
			pn.sentThrottledBytes(data.length);
		}
		if(packet.getFragments().size() == 0) {
			pn.onNotificationOnlyPacketSent(data.length);
		}

		return true;
	}

	NPFPacket createPacket(int maxPacketSize, PeerMessageQueue messageQueue, SessionKey sessionKey, boolean ackOnly) throws BlockedTooLongException {
		
		checkForLostPackets();
		
		NPFPacket packet = new NPFPacket();
		SentPacket sentPacket = new SentPacket(this, sessionKey);
		
		boolean mustSend = false;
		long now = System.currentTimeMillis();
		
		NewPacketFormatKeyContext keyContext = (NewPacketFormatKeyContext) sessionKey.packetContext;
		
		AddedAcks moved = keyContext.addAcks(packet, maxPacketSize, now);
		if(moved != null && moved.anyUrgentAcks)
			mustSend = true;
		
		int numAcks = packet.countAcks();
		
		if(numAcks > MAX_ACKS) {
			mustSend = true;
		}
		
		if(numAcks > 0) {
			if(logDEBUG) Logger.debug(this, "Added acks for "+this+" for "+pn.shortToString());
		}
		
		if(!ackOnly) {
			
			boolean addedFragments = false;

		// Always finish what we have started before considering sending more packets.
		// Anything beyond this is beyond the scope of NPF and is PeerMessageQueue's job.
		for(int i = 0; i < startedByPrio.size(); i++) {
			HashMap<Integer, MessageWrapper> started = startedByPrio.get(i);

			//Try to finish messages that have been started
			synchronized(started) {
				Iterator<MessageWrapper> it = started.values().iterator();
				while(it.hasNext() && packet.getLength() < maxPacketSize) {
					MessageWrapper wrapper = it.next();
					while(packet.getLength() < maxPacketSize) {
						MessageFragment frag = wrapper.getMessageFragment(maxPacketSize - packet.getLength());
						if(frag == null) break;
						mustSend = true;
						addedFragments = true;
						packet.addMessageFragment(frag);
						sentPacket.addFragment(frag);
					}
				}
			}
		}
		
		if(addedFragments) {
			if(logDEBUG) Logger.debug(this, "Added fragments for "+this);
		}
		
		}
		
		if((!mustSend) && packet.getLength() >= (maxPacketSize * 4 / 5)) {
			// Lots of acks to send, send a packet.
			mustSend = true;
		}
		
		if(!mustSend) {
			if(messageQueue.mustSendNow(now) || messageQueue.mustSendSize(packet.getLength(), maxPacketSize))
				mustSend = true;
		}
		
		if((!mustSend) && numAcks > 0) {
			synchronized(bufferUsageLock) {
				if(usedBufferOtherSide > MAX_BUFFER_SIZE / 2)
					mustSend = true;
			}

		}
		
		if(!mustSend) {
			if(moved != null) {
				moved.abort();
			}
			return null;
		}
		
		if(ackOnly && numAcks == 0) return null;
		
		if(!ackOnly) {
		
		fragments:
		for(int i = 0; i < startedByPrio.size(); i++) {
			//Add messages from the message queue
			while ((packet.getLength() + 10) < maxPacketSize) { //Fragment header is max 9 bytes, allow min 1 byte data
				MessageItem item = null;
				item = messageQueue.grabQueuedMessageItem(i);
				if(item == null) break;

				int bufferUsage;
				synchronized(bufferUsageLock) {
					bufferUsage = usedBufferOtherSide;
				}
				if((bufferUsage + item.buf.length) > MAX_BUFFER_SIZE) {
					if(logDEBUG) Logger.debug(this, "Would excede remote buffer size, requeuing and sending packet. Remote at " + bufferUsage);
					messageQueue.pushfrontPrioritizedMessageItem(item);
					break fragments;
				}

				int messageID = getMessageID();
				if(messageID == -1) {
					if(logMINOR) Logger.minor(this, "No availiable message ID, requeuing and sending packet");
					messageQueue.pushfrontPrioritizedMessageItem(item);
					break fragments;
				}
				
				if(logDEBUG) Logger.debug(this, "Allocated "+messageID+" for "+item);

				MessageWrapper wrapper = new MessageWrapper(item, messageID);
				MessageFragment frag = wrapper.getMessageFragment(maxPacketSize - packet.getLength());
				if(frag == null) {
					messageQueue.pushfrontPrioritizedMessageItem(item);
					break;
				}
				packet.addMessageFragment(frag);
				sentPacket.addFragment(frag);

				//Priority of the one we grabbed might be higher than i
				HashMap<Integer, MessageWrapper> queue = startedByPrio.get(item.getPriority());
				synchronized(queue) {
					queue.put(messageID, wrapper);
				}

				synchronized(bufferUsageLock) {
					usedBufferOtherSide += item.buf.length;
					if(logDEBUG) Logger.debug(this, "Added " + item.buf.length + " to remote buffer. Total is now " + usedBufferOtherSide + " for "+pn.shortToString());
				}
			}
		}
		
		}

		if(packet.getLength() == 5) return null;

		int seqNum = keyContext.allocateSequenceNumber(pn);
		if(seqNum == -1) return null;
		packet.setSequenceNumber(seqNum);
		
		if(logDEBUG && ackOnly) {
			Logger.debug(this, "Sending ack-only packet length "+packet.getLength()+" for "+this);
		} else if(logDEBUG && !ackOnly) {
			Logger.debug(this, "Sending packet length "+packet.getLength()+" for "+this);
		}

		if(packet.getFragments().size() > 0) {
			keyContext.sent(sentPacket, seqNum, packet.getLength());
		}

		return packet;
	}
	
	/** For unit tests */
	int countSentPackets(SessionKey key) {
		NewPacketFormatKeyContext keyContext = (NewPacketFormatKeyContext) key.packetContext;
		return keyContext.countSentPackets();
	}
	
	public long timeCheckForLostPackets() {
		long timeCheck = Long.MAX_VALUE;
		double averageRTT = averageRTT();
		SessionKey key = pn.getCurrentKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, ((NewPacketFormatKeyContext)(key.packetContext)).timeCheckForLostPackets(averageRTT));
		key = pn.getPreviousKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, ((NewPacketFormatKeyContext)(key.packetContext)).timeCheckForLostPackets(averageRTT));
		key = pn.getUnverifiedKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, ((NewPacketFormatKeyContext)(key.packetContext)).timeCheckForLostPackets(averageRTT));
		return timeCheck;
	}
	
	private long timeCheckForAcks() {
		long timeCheck = Long.MAX_VALUE;
		SessionKey key = pn.getCurrentKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, ((NewPacketFormatKeyContext)key.packetContext).timeCheckForAcks());
		key = pn.getPreviousKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, ((NewPacketFormatKeyContext)key.packetContext).timeCheckForAcks());
		key = pn.getUnverifiedKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, ((NewPacketFormatKeyContext)key.packetContext).timeCheckForAcks());
		return timeCheck;
	}

	public void checkForLostPackets() {
		if(pn == null) return;
		double averageRTT = averageRTT();
		long curTime = System.currentTimeMillis();
		SessionKey key = pn.getCurrentKeyTracker();
		if(key != null)
			((NewPacketFormatKeyContext)(key.packetContext)).checkForLostPackets(averageRTT, curTime, pn);
		key = pn.getPreviousKeyTracker();
		if(key != null)
			((NewPacketFormatKeyContext)(key.packetContext)).checkForLostPackets(averageRTT, curTime, pn);
		key = pn.getUnverifiedKeyTracker();
		if(key != null)
			((NewPacketFormatKeyContext)(key.packetContext)).checkForLostPackets(averageRTT, curTime, pn);
	}

	public List<MessageItem> onDisconnect() {
		int messageSize = 0;
		List<MessageItem> items = null;
		for(HashMap<Integer, MessageWrapper> queue : startedByPrio) {
			synchronized(queue) {
				if(items == null)
					items = new ArrayList<MessageItem>();
				for(MessageWrapper wrapper : queue.values()) {
					items.add(wrapper.getItem());
					if(logDEBUG)
						messageSize += wrapper.getLength();
				}
				queue.clear();
			}
		}
		synchronized(bufferUsageLock) {
			usedBufferOtherSide -= messageSize;
			if(logDEBUG) Logger.debug(this, "Removed " + messageSize + " from remote buffer. Total is now " + usedBufferOtherSide);
		}
		return items;
	}
	
	/** When do we need to send a packet?
	 * @return 0 if there is anything already in flight. The time that the oldest ack was
	 * queued at plus the lesser of half the RTT or 100ms if there are acks queued. 
	 * Otherwise Long.MAX_VALUE to indicate that we need to get messages from the queue. */
	public long timeNextUrgent() {
		// Is there anything in flight?
		synchronized(startedByPrio) {
			for(HashMap<Integer, MessageWrapper> started : startedByPrio) {
				synchronized(started) {
					for(MessageWrapper wrapper : started.values()) {
						if(wrapper.canSend()) return 0;
					}
				}
			}
		}
		// Check for acks.
		long ret = timeCheckForAcks();
		
		// Always wake up after half an RTT, check whether stuff is lost or needs ack'ing.
		ret = Math.min(ret, System.currentTimeMillis() + Math.min(100, (long)averageRTT()/2));
		return ret;
	}
	
	public boolean canSend() {
		
		boolean canAllocateID;
		
		synchronized(this) {
			// Check whether we can allocate a message number.
			canAllocateID = 
				!seqNumGreaterThan(nextMessageID, (messageWindowPtrAcked + MSG_WINDOW_SIZE) % NUM_MESSAGE_IDS, 28);
		}
		
		if(canAllocateID) {
			// Check whether we need to rekey.
			SessionKey tracker = pn.getCurrentKeyTracker();
			if(tracker == null) return false;
			NewPacketFormatKeyContext keyContext = (NewPacketFormatKeyContext) tracker.packetContext;
			if(!keyContext.canAllocateSeqNum()) {
				// We can't allocate more sequence numbers because we haven't rekeyed yet
				pn.startRekeying();
				Logger.error(this, "Can't send because we would block");
				return false;
			}
		}
		
		if(canAllocateID) {
			int bufferUsage;
			synchronized(bufferUsageLock) {
				bufferUsage = usedBufferOtherSide;
			}
			if((bufferUsage + 200 /* bigger than most messages */ ) > MAX_BUFFER_SIZE) {
				if(logDEBUG) Logger.debug(this, "Cannot send: Would exceed remote buffer size. Remote at " + bufferUsage);
				return false;
			}

		}
		
		return true;
	}

	private long blockedSince = -1;
	private int getMessageID() throws BlockedTooLongException {
		int messageID;
		synchronized(this) {
			if(seqNumGreaterThan(nextMessageID, (messageWindowPtrAcked + MSG_WINDOW_SIZE) % NUM_MESSAGE_IDS, 28)) {
				if(blockedSince == -1) {
					blockedSince = System.currentTimeMillis();
				} else if(System.currentTimeMillis() - blockedSince > MAX_MSGID_BLOCK_TIME) {
					throw new BlockedTooLongException(null, System.currentTimeMillis() - blockedSince);
				}
				return -1;
			}
			blockedSince = -1;
			messageID = nextMessageID++;
			if(nextMessageID == NUM_MESSAGE_IDS) nextMessageID = 0;
		}
		return messageID;
	}

	private double averageRTT() {
		if(pn != null) {
			return pn.averagePingTime();
		}
		return 250;
	}

	static class SentPacket {
		final SessionKey sessionKey;
		NewPacketFormat npf;
		LinkedList<MessageWrapper> messages = new LinkedList<MessageWrapper>();
		LinkedList<int[]> ranges = new LinkedList<int[]>();
		long sentTime;
		int packetLength;

		public SentPacket(NewPacketFormat npf, SessionKey key) {
			this.npf = npf;
			this.sessionKey = key;
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
				
				if(logDEBUG)
					Logger.debug(this, "Acknowledging "+range[0]+" to "+range[1]+" on "+wrapper.getMessageID());

				if(wrapper.ack(range[0], range[1])) {
					HashMap<Integer, MessageWrapper> started = npf.startedByPrio.get(wrapper.getPriority());
					MessageWrapper removed = null;
					synchronized(started) {
						removed = started.remove(wrapper.getMessageID());
					}
					if(removed == null && logMINOR) {
						// ack() can return true more than once, it just only calls the callbacks once.
						Logger.minor(this, "Completed message "+wrapper.getMessageID()+" but it is not in the map from "+wrapper);
					}

					if(removed != null) {
						if(logDEBUG) Logger.debug(this, "Completed message "+wrapper.getMessageID()+" from "+wrapper);
						completedMessagesSize += wrapper.getLength();

						boolean couldSend = npf.canSend();
						synchronized(npf) {
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
						if(!couldSend && npf.canSend()) {
							//We aren't blocked anymore, notify packet sender
							npf.pn.wakeUpSender();
						}
					}
				}
			}

			if(completedMessagesSize > 0) {
				synchronized(npf.bufferUsageLock) {
					npf.usedBufferOtherSide -= completedMessagesSize;
					if(logDEBUG) Logger.debug(this, "Removed " + completedMessagesSize + " from remote buffer. Total is now " + npf.usedBufferOtherSide);
				}
			}

			return System.currentTimeMillis() - sentTime;
		}

		public void lost() {
			int bytesToResend = 0;
			Iterator<MessageWrapper> msgIt = messages.iterator();
			Iterator<int[]> rangeIt = ranges.iterator();

			while(msgIt.hasNext()) {
				MessageWrapper wrapper = msgIt.next();
				int[] range = rangeIt.next();

				bytesToResend += wrapper.lost(range[0], range[1]);
			}

			//Unless we disconnect these will be resent eventually
			if(npf.pn != null) npf.pn.resentBytes(bytesToResend);
		}

		public void sent(int length) {
			sentTime = System.currentTimeMillis();
			this.packetLength = length;
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
			if(logDEBUG) Logger.debug(this, "Resizing from " + buffer.length + " to " + length);

			synchronized(npf.bufferUsageLock) {
				if((npf.usedBuffer + (length - buffer.length)) > MAX_BUFFER_SIZE) {
					if(logMINOR) Logger.minor(this, "Could not resize buffer, would excede max size");
					return false;
				}

				npf.usedBuffer += (length - buffer.length);
				if(logDEBUG) Logger.debug(this, "Added " + (length - buffer.length) + " to buffer. Total is now " + npf.usedBuffer);
			}

			byte[] newBuffer = new byte[length];
			System.arraycopy(buffer, 0, newBuffer, 0, Math.min(length, buffer.length));
			buffer = newBuffer;

			return true;
		}
	}

	public int countSendableMessages() {
		int x = 0;
		synchronized(startedByPrio) {
			for(HashMap<Integer, MessageWrapper> started : startedByPrio) {
				synchronized(started) {
					for(MessageWrapper wrapper : started.values()) {
						if(wrapper.canSend()) x++;
					}
				}
			}
		}
		return x;
	}
}
