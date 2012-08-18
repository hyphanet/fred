/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.LinkedList;

import freenet.crypt.BlockCipher;
import freenet.crypt.HMAC;
import freenet.crypt.PCFBMode;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.io.xfer.PacketThrottle;
import freenet.node.NewPacketFormatKeyContext.AddedAcks;
import freenet.node.PeerMessageTracker.SentPacket;
import freenet.pluginmanager.PluginAddress;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.MutableBoolean;

public class NewPacketFormat implements PacketFormat {

	private final int hmacLength;
	private static final int HMAC_LENGTH = 10;
	// FIXME Use a more efficient structure - int[] or maybe just a big byte[].
	// FIXME increase this significantly to let it ride over network interruptions.
	private static final int NUM_SEQNUMS_TO_WATCH_FOR = 1024;
	static final int MAX_RECEIVE_BUFFER_SIZE = 256 * 1024;
	private static final int NUM_MESSAGE_IDS = 268435456;
	static final long NUM_SEQNUMS = 2147483648l;
	private static final int MAX_ACKS = 500;
	static boolean DO_KEEPALIVES = true;

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
	
	public final PeerPacketTransport peerTransport;
	
	public final PeerMessageTracker pmt;
	
	private long timeLastSentPacket;
	private long timeLastSentPayload;

	public NewPacketFormat(BasePeerNode pn, int ourInitialMsgID, int theirInitialMsgID, PeerPacketTransport peerTransport) {
		this.pn = pn;
		this.peerTransport = peerTransport;
		this.pmt = pn.getPeerMessageTracker();

		// Make sure the numbers are within the ranges we want
		ourInitialMsgID = (ourInitialMsgID & 0x7FFFFFFF) % NUM_MESSAGE_IDS;
		theirInitialMsgID = (theirInitialMsgID & 0x7FFFFFFF) % NUM_MESSAGE_IDS;
		
		hmacLength = HMAC_LENGTH;
	}

	@Override
	public boolean handleReceivedPacket(byte[] buf, int offset, int length, long now, PluginAddress replyTo) {
		NPFPacket packet = null;
		SessionKey s = null;
		for(int i = 0; i < 3; i++) {
			if(i == 0) {
				s = peerTransport.getCurrentKeyTracker();
			} else if (i == 1) {
				s = peerTransport.getPreviousKeyTracker();
			} else {
				s = peerTransport.getUnverifiedKeyTracker();
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

		peerTransport.receivedPacket(false, true);
		peerTransport.verified(s);
		peerTransport.maybeRekey();
		peerTransport.reportIncomingPacket(buf, offset, length, now);

		LinkedList<byte[]> finished = pmt.handleDecryptedPacket(packet, s);
		if(logMINOR && !finished.isEmpty()) 
			Logger.minor(this, "Decoded messages: "+finished.size());
		DecodingMessageGroup group = pn.startProcessingDecryptedMessages(finished.size());
		for(byte[] buffer : finished) {
			group.processDecryptedMessage(buffer, 0, buffer.length, 0);
		}
		group.complete();

		return true;
	}

	private NPFPacket tryDecipherPacket(byte[] buf, int offset, int length, SessionKey sessionKey) {
		NewPacketFormatKeyContext keyContext = sessionKey.packetContext;
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
				if(keyContext.seqNumWatchList[index][j] != buf[offset + hmacLength + j]) continue outer;
			}
			
			int sequenceNumber = (int) ((0l + keyContext.watchListOffset + i) % NUM_SEQNUMS);
			if(logDEBUG) Logger.debug(this, "Received packet matches sequence number " + sequenceNumber);
			// Copy it to avoid side-effects.
			byte[] copy = new byte[length];
			System.arraycopy(buf, offset, copy, 0, length);
			NPFPacket p = decipherFromSeqnum(copy, 0, length, sessionKey, sequenceNumber);
			if(p != null) {
				if(logMINOR) Logger.minor(this, "Received packet " + p.getSequenceNumber()+" on "+sessionKey);
				return p;
			}
		}

		return null;
	}

	/** NOTE: THIS WILL DECRYPT THE DATA IN THE BUFFER !!! */
	private NPFPacket decipherFromSeqnum(byte[] buf, int offset, int length, SessionKey sessionKey, int sequenceNumber) {
		BlockCipher ivCipher = sessionKey.ivCipher;

		byte[] IV = new byte[ivCipher.getBlockSize() / 8];
		System.arraycopy(sessionKey.ivNonce, 0, IV, 0, IV.length);
		IV[IV.length - 4] = (byte) (sequenceNumber >>> 24);
		IV[IV.length - 3] = (byte) (sequenceNumber >>> 16);
		IV[IV.length - 2] = (byte) (sequenceNumber >>> 8);
		IV[IV.length - 1] = (byte) (sequenceNumber);

		ivCipher.encipher(IV, IV);

		byte[] text = new byte[length - hmacLength];
		System.arraycopy(buf, offset + hmacLength, text, 0, text.length);
		byte[] hash = new byte[hmacLength];
		System.arraycopy(buf, offset, hash, 0, hash.length);

		if(!HMAC.verifyWithSHA256(sessionKey.hmacKey, text, hash)) return null;

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.incommingCipher, IV);
		payloadCipher.blockDecipher(buf, offset + hmacLength, length - hmacLength);

		byte[] payload = new byte[length - hmacLength];
		System.arraycopy(buf, offset + hmacLength, payload, 0, length - hmacLength);

		NPFPacket p = NPFPacket.create(payload, pn);

		NewPacketFormatKeyContext keyContext = sessionKey.packetContext;
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

	@Override
	public boolean maybeSendPacket(long now, boolean ackOnly)
	throws BlockedTooLongException {
		SessionKey sessionKey = peerTransport.getPreviousKeyTracker();
		if(sessionKey != null) {
			// Try to sent an ack-only packet.
			if(maybeSendPacket(now, true, sessionKey)) return true;
		}
		sessionKey = peerTransport.getUnverifiedKeyTracker();
		if(sessionKey != null) {
			// Try to sent an ack-only packet.
			if(maybeSendPacket(now, true, sessionKey)) return true;
		}
		sessionKey = peerTransport.getCurrentKeyTracker();
		if(sessionKey == null) {
			Logger.warning(this, "No key for encrypting hash");
			return false;
		}
		return maybeSendPacket(now, ackOnly, sessionKey);
	}
	
	public boolean maybeSendPacket(long now, boolean ackOnly, SessionKey sessionKey)
	throws BlockedTooLongException {
		int maxPacketSize = peerTransport.transportPlugin.getMaxPacketSize();
		NewPacketFormatKeyContext keyContext = sessionKey.packetContext;

		NPFPacket packet = createPacket(maxPacketSize - hmacLength, pn.getMessageQueue(), sessionKey, ackOnly);
		if(packet == null) return false;

		int paddedLen = packet.getLength() + hmacLength;
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
		packet.toBytes(data, hmacLength, pn.paddingGen());

		BlockCipher ivCipher = sessionKey.ivCipher;

		byte[] IV = new byte[ivCipher.getBlockSize() / 8];
		System.arraycopy(sessionKey.ivNonce, 0, IV, 0, IV.length);
		System.arraycopy(data, hmacLength, IV, IV.length - 4, 4);

		ivCipher.encipher(IV, IV);

		PCFBMode payloadCipher = PCFBMode.create(sessionKey.outgoingCipher, IV);
		payloadCipher.blockEncipher(data, hmacLength, paddedLen - hmacLength);

		//Add hash
		byte[] text = new byte[paddedLen - hmacLength];
		System.arraycopy(data, hmacLength, text, 0, text.length);

		byte[] hash = HMAC.macWithSHA256(sessionKey.hmacKey, text, hmacLength);

		System.arraycopy(hash, 0, data, 0, hmacLength);

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
				                + packet.getAcks().size() + " acks on "+this);
			}
			pn.sendEncryptedPacket(data, peerTransport.transportPlugin);
		} catch (LocalAddressException e) {
			Logger.error(this, "Caught exception while sending packet", e);
			return false;
		}
		
		packet.onSent(data.length, pn);

		if(packet.getFragments().size() > 0) {
			keyContext.sent(packet.getSequenceNumber(), packet.getLength());
		}

		now = System.currentTimeMillis();
		peerTransport.sentPacket();
		peerTransport.reportOutgoingPacket(data, 0, data.length, now);
		if(peerTransport.shouldThrottle()) {
			pn.sentThrottledBytes(data.length);
		}
		if(packet.getFragments().size() == 0) {
			pn.onNotificationOnlyPacketSent(data.length);
		}
		
		synchronized(this) {
			if(timeLastSentPacket < now) timeLastSentPacket = now;
			if(packet.getFragments().size() > 0) {
				if(timeLastSentPayload < now) timeLastSentPayload = now;
			}
		}

		return true;
	}

	NPFPacket createPacket(int maxPacketSize, PeerMessageQueue messageQueue, SessionKey sessionKey, boolean ackOnly) throws BlockedTooLongException {
		
		checkForLostPackets();
		
		NPFPacket packet = new NPFPacket();
		SentPacket sentPacket = new SentPacket(this, sessionKey);
		boolean mustSend = false;
		long now = System.currentTimeMillis();
		NewPacketFormatKeyContext keyContext = sessionKey.packetContext;
		
		AddedAcks moved = keyContext.addAcks(packet, maxPacketSize, now);
		if(moved != null && moved.anyUrgentAcks) {
			if(logDEBUG) Logger.debug(this, "Must send because urgent acks");
			mustSend = true;
		}
		
		int numAcks = packet.countAcks();
		if(numAcks > MAX_ACKS) {
			mustSend = true;
		}
		if(numAcks > 0) {
			if(logDEBUG) Logger.debug(this, "Added acks for "+this+" for "+pn.shortToString());
		}
		
		byte[] haveAddedStatsBulk = null;
		byte[] haveAddedStatsRT = null;
		
		if(!ackOnly) {
			
			boolean addedFragments = false;
			MutableBoolean addStatsBulk = new MutableBoolean();
			MutableBoolean addStatsRT = new MutableBoolean();
			boolean addedStatsBulk = addStatsBulk.value = false;
			boolean addedStatsRT = addStatsRT.value = false;
			
			while(true) {
				while(packet.getLength() < maxPacketSize) {
					MessageFragment frag = pmt.getMessageFragment(maxPacketSize - packet.getLength(), addStatsBulk, addStatsRT);
					if(frag == null) break;
					mustSend = true;
					addedFragments = true;
					packet.addMessageFragment(frag);
					sentPacket.addFragment(frag);
				}
				
				if(!(addStatsBulk.value || addStatsRT.value)) break;
				
				if(addStatsBulk.value && !addedStatsBulk) {
					MessageItem item = pn.makeLoadStats(false, false, true);
					if(item != null) {
						byte[] buf = item.getData();
						haveAddedStatsBulk = buf;
						// FIXME if this fails, drop some messages.
						packet.addLossyMessage(buf, maxPacketSize);
						addedStatsBulk = true;
					}
				}
				
				if(addStatsRT.value && !addedStatsRT) {
					MessageItem item = pn.makeLoadStats(true, false, true);
					if(item != null) {
						byte[] buf = item.getData();
						haveAddedStatsRT = buf;
						// FIXME if this fails, drop some messages.
						packet.addLossyMessage(buf, maxPacketSize);
						addedStatsRT = true;
					}
				}
			}
			
			if(addedFragments) {
				if(logDEBUG) Logger.debug(this, "Added fragments for "+this+" (must send)");
			}
		}
		
		if((!mustSend) && packet.getLength() >= (maxPacketSize * 4 / 5)) {
			if(logDEBUG) Logger.debug(this, "Must send because packet is big on acks alone");
			// Lots of acks to send, send a packet.
			mustSend = true;
		}
		
		if((!ackOnly) && (!mustSend)) {
			if(messageQueue.mustSendNow(now) || messageQueue.mustSendSize(packet.getLength(), maxPacketSize)) {
				if(logDEBUG) Logger.debug(this, "Must send because of message queue");
				mustSend = true;
			}
		}
		
		if((!mustSend) && numAcks > 0) {
			int maxSendBufferSize = maxSendBufferSize();
			int sendBuffer = pmt.getSendBufferSize();
			if(sendBuffer > maxSendBufferSize / 2) {
				if(logDEBUG) Logger.debug(this, "Must send because other side buffer size is "+sendBuffer);
				mustSend = true;
			}
		}
		
		boolean checkedCanSend = false;
		boolean cantSend = false;
		boolean mustSendKeepalive = false;
		
		if(DO_KEEPALIVES) {
			synchronized(this) {
				if(!mustSend) {
					if(now - timeLastSentPacket > Node.KEEPALIVE_INTERVAL)
						mustSend = true;
				}
				if((!ackOnly) && now - timeLastSentPayload > Node.KEEPALIVE_INTERVAL && 
						packet.getFragments().isEmpty())
					mustSendKeepalive = true;
			}
		}
		
		if(mustSendKeepalive) {
			if(!checkedCanSend)
				cantSend = !canSend(sessionKey);
			checkedCanSend = true;
			if(!cantSend) {
				mustSend = true;
			}
		}
		
		if(!mustSend) {
			if(moved != null) {
				moved.abort();
			}
			return null;
		}
		
		boolean sendStatsBulk = false, sendStatsRT = false;
		
		if(!ackOnly) {
			
			sendStatsBulk = pn.grabSendLoadStatsASAP(false);
			sendStatsRT = pn.grabSendLoadStatsASAP(true);
			
			if(sendStatsBulk || sendStatsRT) {
				if(!checkedCanSend)
					cantSend = !canSend(sessionKey);
				checkedCanSend = true;
				if(cantSend) {
					if(sendStatsBulk)
						pn.setSendLoadStatsASAP(false);
					if(sendStatsRT)
						pn.setSendLoadStatsASAP(true);
				} else {
					mustSend = true;
				}
			}
		}
		
		if(ackOnly && numAcks == 0) return null;
		
		if((!ackOnly) && (!cantSend)) {
			
			if(sendStatsBulk) {
				MessageItem item = pn.makeLoadStats(false, true, false);
				if(item != null) {
					if(haveAddedStatsBulk != null) {
						packet.removeLossyMessage(haveAddedStatsBulk);
					}
					messageQueue.pushfrontPrioritizedMessageItem(item);
					haveAddedStatsBulk = item.buf;
				}
			}
			
			if(sendStatsRT) {
				MessageItem item = pn.makeLoadStats(true, true, false);
				if(item != null) {
					if(haveAddedStatsRT != null) {
						packet.removeLossyMessage(haveAddedStatsRT);
					}
					messageQueue.pushfrontPrioritizedMessageItem(item);
					haveAddedStatsRT = item.buf;
				}
			}
			
			// Load new message fragments into the in-flight queue if necessary. Use the ping fragment.
			MessageFragment ping = pmt.loadMessageFragments(now, true, maxPacketSize - packet.getLength());
			if(mustSendKeepalive && packet.noFragments()) {
				if(ping != null) {
					packet.addMessageFragment(ping);
					sentPacket.addFragment(ping);
				}
			}
			
			MutableBoolean addStatsBulk = new MutableBoolean();
			MutableBoolean addStatsRT = new MutableBoolean();
			boolean addedStatsBulk = addStatsBulk.value = false;
			boolean addedStatsRT = addStatsRT.value = false;
			
			while(true) {
				while(packet.getLength() < maxPacketSize) {
					MessageFragment frag = pmt.getMessageFragment(maxPacketSize - packet.getLength(), addStatsBulk, addStatsRT);
					if(frag == null) break;
					mustSend = true;
					packet.addMessageFragment(frag);
					sentPacket.addFragment(frag);
				}
				
				if(!(addStatsBulk.value || addStatsRT.value)) break;
				
				if(addStatsBulk.value && !addedStatsBulk) {
					MessageItem item = pn.makeLoadStats(false, false, true);
					if(item != null) {
						byte[] buf = item.getData();
						haveAddedStatsBulk = buf;
						// FIXME if this fails, drop some messages.
						packet.addLossyMessage(buf, maxPacketSize);
						addedStatsBulk = true;
					}
				}
				
				if(addStatsRT.value && !addedStatsRT) {
					MessageItem item = pn.makeLoadStats(true, false, true);
					if(item != null) {
						byte[] buf = item.getData();
						haveAddedStatsRT = buf;
						// FIXME if this fails, drop some messages.
						packet.addLossyMessage(buf, maxPacketSize);
						addedStatsRT = true;
					}
				}
			}
		}

		if(packet.getLength() == 5) return null;

		int seqNum = keyContext.allocateSequenceNumber(peerTransport);
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
	
	static final int MAX_MESSAGE_SIZE = 2048;
	
	private int maxSendBufferSize() {
		return MAX_RECEIVE_BUFFER_SIZE;
	}

	/** For unit tests */
	int countSentPackets(SessionKey key) {
		NewPacketFormatKeyContext keyContext = key.packetContext;
		return keyContext.countSentPackets();
	}
	
	@Override
	public long timeCheckForLostPackets() {
		long timeCheck = Long.MAX_VALUE;
		double averageRTT = averageRTT();
		SessionKey key = peerTransport.getCurrentKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, ((key.packetContext)).timeCheckForLostPackets(averageRTT));
		key = peerTransport.getPreviousKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, ((key.packetContext)).timeCheckForLostPackets(averageRTT));
		key = peerTransport.getUnverifiedKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, ((key.packetContext)).timeCheckForLostPackets(averageRTT));
		return timeCheck;
	}
	
	private long timeCheckForAcks() {
		long timeCheck = Long.MAX_VALUE;
		SessionKey key = peerTransport.getCurrentKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, (key.packetContext).timeCheckForAcks());
		key = peerTransport.getPreviousKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, (key.packetContext).timeCheckForAcks());
		key = peerTransport.getUnverifiedKeyTracker();
		if(key != null)
			timeCheck = Math.min(timeCheck, (key.packetContext).timeCheckForAcks());
		return timeCheck;
	}

	@Override
	public void checkForLostPackets() {
		if(pn == null) return;
		double averageRTT = averageRTT();
		long curTime = System.currentTimeMillis();
		SessionKey key = peerTransport.getCurrentKeyTracker();
		if(key != null)
			((key.packetContext)).checkForLostPackets(averageRTT, curTime, pn);
		key = peerTransport.getPreviousKeyTracker();
		if(key != null)
			((key.packetContext)).checkForLostPackets(averageRTT, curTime, pn);
		key = peerTransport.getUnverifiedKeyTracker();
		if(key != null)
			((key.packetContext)).checkForLostPackets(averageRTT, curTime, pn);
	}

	@Override
	public void onDisconnect() {
		// Have something specific to this transport.
		// Previous code has been moved to PeerMessageTracker
	}
	
	/** When do we need to send a packet?
	 * @return 0 if there is anything already in flight. The time that the oldest ack was
	 * queued at plus the lesser of half the RTT or 100ms if there are acks queued. 
	 * Otherwise Long.MAX_VALUE to indicate that we need to get messages from the queue. */
	@Override
	public long timeNextUrgent(boolean canSend) {
		long ret = Long.MAX_VALUE;
		if(canSend) {
			ret = pmt.timeNextUrgent(canSend);
		}
		// Check for acks.
		ret = Math.min(ret, timeCheckForAcks());
		
		// Always wake up after half an RTT, check whether stuff is lost or needs ack'ing.
		ret = Math.min(ret, System.currentTimeMillis() + Math.min(100, (long)averageRTT()/2));
		return ret;
	}
	
	@Override
	public long timeSendAcks() {
		return timeCheckForAcks();
	}
	
	@Override
	public boolean canSend(SessionKey tracker) {
		// Check whether we need to rekey.
		if(tracker == null) return false;
		NewPacketFormatKeyContext keyContext = tracker.packetContext;
		if(!keyContext.canAllocateSeqNum()) {
			// We can't allocate more sequence numbers because we haven't rekeyed yet
			peerTransport.startRekeying();
			Logger.error(this, "Can't send because we would block on "+this);
			return false;
		}
		if(tracker != null && pn != null) {
			PacketThrottle throttle = pn.getThrottle();
			if(throttle == null) {
				// Ignore
			} else {
				int maxPackets = (int)Math.min(Integer.MAX_VALUE, pn.getThrottle().getWindowSize());
				// Impose a minimum so that we don't lose the ability to send anything.
				if(maxPackets < 1) maxPackets = 1;
				NewPacketFormatKeyContext packets = tracker.packetContext;
				if(maxPackets <= packets.countSentPackets()) {
					// FIXME some packets will be visible from the outside yet only contain acks.
					// SECURITY/INVISIBILITY: They won't count here, this is bad.
					// However, counting packets in flight, rather than bytes of messages, is the right solution:
					// 1. It's closer to what TCP does.
					// 2. It avoids needing to have an excessively high minimum window size.
					// 3. It allows us to start work on any message even if it's big, while still having reasonably accurate congestion control.
					// This prevents us from getting into a situation where we never use up the full window but can never send big messages either.
					// 4. It's closer to what we used to do (only limit big packets), which seemed to work mostly.
					// 5. It avoids some complicated headaches with PeerMessageQueue. E.g. we need to avoid requeueing.
					// 6. In spite of the issue with acks, it's probably more "invisible" on the whole, in that the number of packets is visible,
					// whereas messages are supposed to not be visible.
					// Arguably we should count bytes rather than packets.
					if(logDEBUG) Logger.debug(this, "Cannot send because "+packets.countSentPackets()+" in flight of limit "+maxPackets+" on "+this);
					return false;
				}
			}
		}
		return true;
	}

	private double averageRTT() {
		if(pn != null) {
			return pn.averagePingTimeCorrected();
		}
		return PeerNode.MIN_RTO;
	}

	@Override
	public String toString() {
		if(pn != null) return super.toString() +" for "+pn.shortToString();
		else return super.toString();
	}

	@Override
	public boolean fullPacketQueued(int maxPacketSize) {
		return pn.getMessageQueue().mustSendSize(HMAC_LENGTH /* FIXME estimate headers */, maxPacketSize);
	}
}
