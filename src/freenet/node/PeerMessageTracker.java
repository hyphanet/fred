package freenet.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.xfer.PacketThrottle;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.MutableBoolean;
import freenet.support.SparseBitmap;
import freenet.support.Logger.LogLevel;

/**
 * This class has been created to isolate the Message tracking portions of code in NewPacketFormat
 * @author chetan
 *
 */
public class PeerMessageTracker {
	
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
	
	static final int MAX_MESSAGE_SIZE = 2048;
	static final int MAX_RECEIVE_BUFFER_SIZE = 256 * 1024;
	private static final int MSG_WINDOW_SIZE = 65536;
	private static final int NUM_MESSAGE_IDS = 268435456;
	static final long NUM_SEQNUMS = 2147483648l;
	private static final int MAX_MSGID_BLOCK_TIME = 10 * 60 * 1000;
	private static final int MAX_ACKS = 500;
	static boolean DO_KEEPALIVES = true;
	
	/** 
	 * The actual buffer of outgoing messages that have not yet been acked.
	 * LOCKING: Protected by sendBufferLock. 
	 */
	private final ArrayList<HashMap<Integer, MessageWrapper>> startedByPrio;
	
	public final PeerNode pn;
	
	public PeerMessageQueue messageQueue;
	
	/** The next message ID for outgoing messages.
	 * LOCKING: Protected by (this). */
	private int nextMessageID;
	/** The first message id that hasn't been acked by the receiver.
	 * LOCKING: Protected by (this). */
	private int messageWindowPtrAcked;
	/** All messages that have been acked (we remove those which are out of window to
	 * limit space usage).
	 * LOCKING: Protected by (this). */
	private final SparseBitmap ackedMessages = new SparseBitmap();
	
	private final HashMap<Integer, PartiallyReceivedBuffer> receiveBuffers = new HashMap<Integer, PartiallyReceivedBuffer>();
	private final HashMap<Integer, SparseBitmap> receiveMaps = new HashMap<Integer, SparseBitmap>();
	/** The first message id that hasn't been fully received */
	private int messageWindowPtrReceived;
	private final SparseBitmap receivedMessages= new SparseBitmap();
	
	/** How much of our receive buffer have we used? Equal to how much is used of the
	 * sender's send buffer. The receive buffer is actually implemented in receiveBuffers.
	 * LOCKING: Protected by receiveBufferSizeLock. */
	private int receiveBufferUsed = 0;
	/** How much of the other side's buffer have we used? Or alternatively, how much space
	 * have we used in our send buffer, namely startedByPrio? 
	 * LOCKING: Protected by sendBufferLock */
	private int sendBufferUsed = 0;
	/** Lock protecting buffer usage counters, and the buffer itself (startedByPrio). 
	 * MUST BE TAKEN LAST. 
	 * Justification: The outgoing buffer and the buffer usage should be protected by 
	 * the same lock, for consistency. The buffer usage and the connection status must
	 * be protected by the same lock, so we don't send packets when we are disconnected 
	 * and get race conditions in onDisconnect(). The incoming buffer estimate could be
	 * separated in theory. */
	private final Object sendBufferLock = new Object();
	/** Lock protecting the size of the receive buffer. */
	private final Object receiveBufferSizeLock = new Object();
	
	
	private int pingCounter;
	
	public PeerMessageTracker(PeerNode pn) {
		
		this.pn = pn;
		messageQueue = pn.getMessageQueue();
		startedByPrio = new ArrayList<HashMap<Integer, MessageWrapper>>(DMT.NUM_PRIORITIES);
		for(int i = 0; i < DMT.NUM_PRIORITIES; i++) {
			startedByPrio.add(new HashMap<Integer, MessageWrapper>());
		}
	}
	
	/**
	 * 
	 */
	public synchronized MessageFragment getMessageFragment(int messageLength, MutableBoolean addStatsBulk, MutableBoolean addStatsRT) {
		synchronized(sendBufferLock) {
			// Always finish what we have started before considering sending more packets.
			// Anything beyond this is beyond the scope of NPF and is PeerMessageQueue's job.
			for(int i = 0; i < startedByPrio.size(); i++) {
				HashMap<Integer, MessageWrapper> started = startedByPrio.get(i);
				
				//Try to finish messages that have been started
				Iterator<MessageWrapper> it = started.values().iterator();
				while(it.hasNext()) {
					MessageWrapper wrapper = it.next();
					MessageFragment frag = wrapper.getMessageFragment(messageLength);
					if(frag == null) continue;
					if(wrapper.allSent()) {
						if(wrapper.getItem().sendLoadBulk) {
							addStatsBulk.value = true;
							break;
						}
						if(wrapper.getItem().sendLoadRT) {
							addStatsRT.value = true;
							break;
						}
					}
					return frag;
				}
			}
		}
		return null;
	}
	
	public synchronized MessageFragment loadMessageFragments(long now, boolean needPingMessage, int pingLength) throws BlockedTooLongException {
		synchronized(sendBufferLock) {
			if(sendBufferUsed > 0)
				return null;
		}
		MessageFragment frag = null;
		fragments:
			for(int i = 0; i < startedByPrio.size(); i++) {

				prio:
				while(true) {
					
					if(!canSend()) break;

					boolean wasGeneratedPing = false;
					MessageItem item = null;
					item = messageQueue.grabQueuedMessageItem(i);
					if(item == null) {
						if(needPingMessage) {
							// Create a ping for keepalive purposes.
							// It will be acked, this ensures both sides don't timeout.
							Message msg;
							synchronized(this) {
								msg = DMT.createFNPPing(pingCounter++);
								wasGeneratedPing = true;
							}
							item = new MessageItem(msg, null, null);
							item.setDeadline(now + PacketSender.MAX_COALESCING_DELAY);
						} else {
							break prio;
						}
					}
					
					int messageID = getMessageID();
					if(messageID == -1) {
						// CONCURRENCY: This will fail sometimes if we send messages to the same peer from different threads.
						// This doesn't happen at the moment because we use a single PacketSender for all ports and all peers.
						// We might in future split it across multiple threads but it'd be best to keep the same peer on the same thread.
						Logger.error(this, "No availiable message ID, requeuing and sending packet (we already checked didn't we???)");
						if(!wasGeneratedPing) {
							messageQueue.pushfrontPrioritizedMessageItem(item);
							// No point adding to queue if it's just a ping:
							//  We will try again next time.
							//  But odds are the connection is broken and the other side isn't responding...
						}
						break fragments;
					}
					
					if(logDEBUG) Logger.debug(this, "Allocated "+messageID+" for "+item+" for "+this);
					
					MessageWrapper wrapper = new MessageWrapper(item, messageID);
					if(needPingMessage) {
						frag = wrapper.getMessageFragment(pingLength);
						//After we have one ping message, don't create more
						needPingMessage = false;
					}
					
					//Priority of the one we grabbed might be higher than i
					HashMap<Integer, MessageWrapper> queue = startedByPrio.get(item.getPriority());
					synchronized(sendBufferLock) {
						// CONCURRENCY: This could go over the limit if we allow createPacket() for the same node on two threads in parallel. That's probably a bad idea anyway.
						sendBufferUsed += item.buf.length;
						if(logDEBUG) Logger.debug(this, "Added " + item.buf.length + " to remote buffer. Total is now " + sendBufferUsed + " for "+pn.shortToString());
						queue.put(messageID, wrapper);
					}
				}						
			}
		return frag;
	}
	
	
	
	private long blockedSince = -1;
	private int getMessageID() throws BlockedTooLongException {
		int messageID;
		synchronized(this) {
			if(seqNumGreaterThan(nextMessageID, (messageWindowPtrAcked + MSG_WINDOW_SIZE) % NUM_MESSAGE_IDS, 28)) {
				if(blockedSince == -1) {
					blockedSince = System.currentTimeMillis();
				} else if(System.currentTimeMillis() - blockedSince > MAX_MSGID_BLOCK_TIME) {
					throw new BlockedTooLongException(System.currentTimeMillis() - blockedSince);
				}
				return -1;
			}
			blockedSince = -1;
			messageID = nextMessageID++;
			if(nextMessageID == NUM_MESSAGE_IDS) nextMessageID = 0;
		}
		return messageID;
	}
	
	private boolean seqNumGreaterThan(long i1, long i2, int serialBits) {
		//halfValue is half the window of possible numbers, so this returns true if the distance from
		//i2->i1 is smaller than i1->i2. See RFC1982 for details and limitations.

		long halfValue = (long) Math.pow(2, serialBits - 1);
		return (((i1 < i2) && ((i2 - i1) > halfValue)) || ((i1 > i2) && (i1 - i2 < halfValue)));
	}
	
	LinkedList<byte[]> handleDecryptedPacket(NPFPacket packet, SessionKey sessionKey) {
		LinkedList<byte[]> fullyReceived = new LinkedList<byte[]>();

		NewPacketFormatKeyContext keyContext = sessionKey.packetContext;
		for(int ack : packet.getAcks()) {
			keyContext.ack(ack, pn, sessionKey);
		}
		
		boolean dontAck = false;
		boolean wakeUp = false;
		if(packet.getError() || (packet.getFragments().size() == 0)) {
			if(logMINOR) Logger.minor(this, "Not acking because " + (packet.getError() ? "error" : "no fragments"));
			dontAck = true;
		}
		ArrayList<Message> lossyMessages = null;
		List<byte[]> l = packet.getLossyMessages();
		if(l != null && !l.isEmpty())
			lossyMessages = new ArrayList<Message>(l.size());
		for(byte[] buf : packet.getLossyMessages()) {
			// FIXME factor out parsing once we are sure these are not bogus.
			// For now we have to be careful.
			Message msg = Message.decodeMessageLax(buf, pn, 0);
			if(msg == null) {
				lossyMessages.clear();
				break;
			}
			if(!msg.getSpec().isLossyPacketMessage()) {
				lossyMessages.clear();
				break;
			}
			lossyMessages.add(msg);
		}
		if(lossyMessages != null) {
			// Handle them *before* the rest.
			if(logMINOR && lossyMessages.size() > 0) Logger.minor(this, "Successfully parsed "+lossyMessages.size()+" lossy packet messages");
			for(Message msg : lossyMessages)
				pn.handleMessage(msg);
		}
		for(MessageFragment fragment : packet.getFragments()) {
			if(messageWindowPtrReceived + MSG_WINDOW_SIZE > NUM_MESSAGE_IDS) {
				int upperBound = (messageWindowPtrReceived + MSG_WINDOW_SIZE) % NUM_MESSAGE_IDS;
				if((fragment.messageID > upperBound) && (fragment.messageID < messageWindowPtrReceived)) {
					if(logMINOR) Logger.minor(this, "Received message "+fragment.messageID+" outside window, acking");
					continue;
				}
			} else {
				int upperBound = messageWindowPtrReceived + MSG_WINDOW_SIZE;
				if(!((fragment.messageID >= messageWindowPtrReceived) && (fragment.messageID < upperBound))) {
					if(logMINOR) Logger.minor(this, "Received message "+fragment.messageID+" outside window, acking");
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
					synchronized(receiveBufferSizeLock) {
						if((receiveBufferUsed + fragment.fragmentLength) > MAX_RECEIVE_BUFFER_SIZE) {
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

				synchronized(receivedMessages) {
					if(receivedMessages.contains(fragment.messageID, fragment.messageID)) continue;
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

				synchronized(sendBufferLock) {
					receiveBufferUsed -= recvBuffer.messageLength;
					if(logDEBUG) Logger.debug(this, "Removed " + recvBuffer.messageLength + " from buffer. Total is now " + receiveBufferUsed);
				}

				fullyReceived.add(recvBuffer.buffer);
				
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
					synchronized(sendBufferLock) {
						if(receiveBufferUsed > MAX_RECEIVE_BUFFER_SIZE / 2)
							wakeUp = true;
					}
				}
				if(wakeUp)
					pn.wakeUpSender();
			}
		}


		return fullyReceived;
	}
	
	
	public static class PartiallyReceivedBuffer {
		private int messageLength;
		private byte[] buffer;
		private PeerMessageTracker pmt;

		private PartiallyReceivedBuffer(PeerMessageTracker pmt) {
			messageLength = -1;
			buffer = new byte[0];
			this.pmt = pmt;
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

			synchronized(pmt.receiveBufferSizeLock) {
				if((pmt.receiveBufferUsed + (length - buffer.length)) > MAX_RECEIVE_BUFFER_SIZE) {
					if(logMINOR) Logger.minor(this, "Could not resize buffer, would excede max size");
					return false;
				}

				pmt.receiveBufferUsed += (length - buffer.length);
				if(logDEBUG) Logger.debug(this, "Added " + (length - buffer.length) + " to buffer. Total is now " + pmt.receiveBufferUsed);
			}

			byte[] newBuffer = new byte[length];
			System.arraycopy(buffer, 0, newBuffer, 0, Math.min(length, buffer.length));
			buffer = newBuffer;

			return true;
		}
	}
	
	/**
	 * Check if we can send based on 
	 * whether if we can allot an ID or whether we are going beyond the buffer usage.
	 * Unlike the NPF method this is not dependent on the SessionKey
	 * @return
	 */
	public boolean canSend() {
		boolean canAllocateID;
		synchronized(this) {
			// Check whether we can allocate a message number.
			canAllocateID = 
				!seqNumGreaterThan(nextMessageID, (messageWindowPtrAcked + MSG_WINDOW_SIZE) % NUM_MESSAGE_IDS, 28);
		}
		if(canAllocateID) {
			int bufferUsage;
			synchronized(sendBufferLock) {
				bufferUsage = sendBufferUsed;
			}
			int maxSendBufferSize = maxSendBufferSize();
			if((bufferUsage + MAX_MESSAGE_SIZE) > maxSendBufferSize) {
				if(logDEBUG) Logger.debug(this, "Cannot send: Would exceed remote buffer size. Remote at " + bufferUsage+" max is "+maxSendBufferSize+" on "+this);
				return false;
			}
		} else {
			synchronized(sendBufferLock) {
				for(HashMap<Integer, MessageWrapper> started : startedByPrio) {
					for(MessageWrapper wrapper : started.values()) {
						if(!wrapper.allSent()) return true;
					}
				}
			}
		}
		if(logDEBUG && !canAllocateID) Logger.debug(this, "Cannot send because cannot allocate ID on "+this);
		return canAllocateID;
	}
	
	private int maxSendBufferSize() {
		return MAX_RECEIVE_BUFFER_SIZE;
	}

}
