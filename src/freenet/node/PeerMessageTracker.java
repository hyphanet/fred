package freenet.node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
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
	private static final int MAX_MSGID_BLOCK_TIME = 10 * 60 * 1000;
	
	/** 
	 * The actual buffer of outgoing messages that have not yet been acked.
	 * LOCKING: Protected by sendBufferLock. 
	 */
	private final ArrayList<HashMap<Integer, MessageWrapper>> startedByPrio;
	
	private final BasePeerNode pn;
	
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
	
	private int pingCounter;
	
	public PeerMessageTracker(BasePeerNode pn, int ourInitialMsgID, int theirInitialMsgID) {
		
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
	
	/**
	 * 
	 */
	public synchronized MessageFragment getMessageFragment(int messageLength, MutableBoolean addStatsBulk, MutableBoolean addStatsRT) {
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
					}
					if(wrapper.getItem().sendLoadRT) {
						addStatsRT.value = true;
					}
				}
				return frag;
			}
		}
		return null;
	}
	
	public synchronized MessageFragment loadMessageFragments(long now, int messageLength, MutableBoolean needPingMessage, MutableBoolean addStatsBulk, MutableBoolean addStatsRT) throws BlockedTooLongException {

		MessageFragment frag = getMessageFragment(messageLength, addStatsBulk, addStatsRT);
		if(frag != null)
			return frag;
		final PeerMessageQueue messageQueue = pn.getMessageQueue();
		/**
		 * 1. We break out of the inner loop when we run out of messages
		 * in the MessageQueue for a particular priority.
		 * 2. We break out of the main loop when we have used up the buffer (canSend method)
		 * and when we can't allocate any more message IDs.
		 * 
		 */
		outerloop:
			for(int i = 0; i < startedByPrio.size(); i++) {
				while(true) {
					if(!canSend()) break outerloop;
					boolean wasGeneratedPing = false;
					MessageItem item = null;
					item = messageQueue.grabQueuedMessageItem(i);
					if(item == null) {
						if(needPingMessage.value) {
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
							// We break out of the inner loop
							break;
						}
					}
					
					int messageID = getMessageID();
					if(messageID == -1) {
						Logger.error(this, "No availiable message ID, requeuing and sending packet (we already checked didn't we???)");
						if(!wasGeneratedPing) {
							messageQueue.pushfrontPrioritizedMessageItem(item);
							// No point adding to queue if it's just a ping:
							// We will try again next time.
							// But odds are the connection is broken and the other side isn't responding...
						}
						break outerloop;
					}
					
					if(logDEBUG) Logger.debug(this, "Allocated "+messageID+" for "+item+" for "+this);
					
					MessageWrapper wrapper = new MessageWrapper(item, messageID);
					if(wasGeneratedPing) {
						// Get a fragment only if it is a ping message, otherwise we do it at the end.
						frag = wrapper.getMessageFragment(messageLength);
						//After we have one ping message, don't create more
						needPingMessage.value = false;
					}
					
					//Priority of the one we grabbed might be higher than i
					HashMap<Integer, MessageWrapper> queue = startedByPrio.get(item.getPriority());
					sendBufferUsed += item.buf.length;
					if(logDEBUG) Logger.debug(this, "Added " + item.buf.length + " to remote buffer. Total is now " + sendBufferUsed + " for "+pn.shortToString());
					queue.put(messageID, wrapper);
				}						
			}
		if(frag == null)
			frag = getMessageFragment(messageLength, addStatsBulk, addStatsRT);
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
	
	synchronized byte[] handleMessageFragment(MessageFragment fragment, MutableBoolean dontAck) {
		
		byte[] fullyReceived = null;
		
		if(messageWindowPtrReceived + MSG_WINDOW_SIZE > NUM_MESSAGE_IDS) {
			int upperBound = (messageWindowPtrReceived + MSG_WINDOW_SIZE) % NUM_MESSAGE_IDS;
			if((fragment.messageID > upperBound) && (fragment.messageID < messageWindowPtrReceived)) {
				if(logMINOR) Logger.minor(this, "Received message "+fragment.messageID+" outside window, acking");
				return null;
			}
		} else {
			int upperBound = messageWindowPtrReceived + MSG_WINDOW_SIZE;
			if(!((fragment.messageID >= messageWindowPtrReceived) && (fragment.messageID < upperBound))) {
				if(logMINOR) Logger.minor(this, "Received message "+fragment.messageID+" outside window, acking");
				return null;
			}
		}
		
		if(receivedMessages.contains(fragment.messageID, fragment.messageID)) return null;

		PartiallyReceivedBuffer recvBuffer = receiveBuffers.get(fragment.messageID);
		SparseBitmap recvMap = receiveMaps.get(fragment.messageID);
		if(recvBuffer == null) {
			if(logMINOR) Logger.minor(this, "Message id " + fragment.messageID + ": Creating buffer");

			recvBuffer = new PartiallyReceivedBuffer(this);
			if(fragment.firstFragment) {
				if(!recvBuffer.setMessageLength(fragment.messageLength)) {
					dontAck.value = true;
					return null;
				}
			} else {
				if((receiveBufferUsed + fragment.fragmentLength) > MAX_RECEIVE_BUFFER_SIZE) {
					if(logMINOR) Logger.minor(this, "Could not create buffer, would excede max size");
					dontAck.value = true;
					return null;
				}
			}

			recvMap = new SparseBitmap();
			receiveBuffers.put(fragment.messageID, recvBuffer);
			receiveMaps.put(fragment.messageID, recvMap);
		} else {
			if(fragment.firstFragment) {
				if(!recvBuffer.setMessageLength(fragment.messageLength)) {
					dontAck.value = true;
					return null;
				}
			}
		}

		if(!recvBuffer.add(fragment.fragmentData, fragment.fragmentOffset)) {
			dontAck.value = true;
			return null;
		}
		if(fragment.fragmentLength == 0) {
			Logger.warning(this, "Received fragment of length 0");
			return null;
		}
		recvMap.add(fragment.fragmentOffset, fragment.fragmentOffset + fragment.fragmentLength - 1);
		if((recvBuffer.messageLength != -1) && recvMap.contains(0, recvBuffer.messageLength - 1)) {
			receiveBuffers.remove(fragment.messageID);
			receiveMaps.remove(fragment.messageID);

			if(receivedMessages.contains(fragment.messageID, fragment.messageID)) 
				return null;
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

			receiveBufferUsed -= recvBuffer.messageLength;
			if(logDEBUG) Logger.debug(this, "Removed " + recvBuffer.messageLength + " from buffer. Total is now " + receiveBufferUsed);

			fullyReceived = recvBuffer.buffer;
			
			if(logMINOR) 
				Logger.minor(this, "Message id " + fragment.messageID + ": Completed");
		} else {
			if(logDEBUG) 
				Logger.debug(this, "Message id " + fragment.messageID + ": " + recvMap);
		}
		return fullyReceived;
	}
	
	
	
	static class PartiallyReceivedBuffer {
		private int messageLength;
		private byte[] buffer;
		private final PeerMessageTracker pmt;

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

			synchronized(pmt) {
				if((pmt.receiveBufferUsed + (length - buffer.length)) > MAX_RECEIVE_BUFFER_SIZE) {
					if(logMINOR) Logger.minor(this, "Could not resize buffer, would excede max size");
					return false;
				}

				pmt.receiveBufferUsed += (length - buffer.length);
				if(logDEBUG) Logger.debug(this, "Added " + (length - buffer.length) + " to buffer. Total is now " + pmt.receiveBufferUsed);
			}

			buffer = Arrays.copyOf(buffer, length);

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
			synchronized(this) {
				bufferUsage = sendBufferUsed;
			}
			int maxSendBufferSize = maxSendBufferSize();
			if((bufferUsage + MAX_MESSAGE_SIZE) > maxSendBufferSize) {
				if(logDEBUG) Logger.debug(this, "Cannot send: Would exceed remote buffer size. Remote at " + bufferUsage+" max is "+maxSendBufferSize+" on "+this);
				return false;
			}
		} else {
			synchronized(this) {
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
	
	int maxSendBufferSize() {
		return MAX_RECEIVE_BUFFER_SIZE;
	}
	
	/**
	 * This checks the messages for their deadlines only.
	 * Refer to NewPacketFormat for the packet related timeNextUrgent
	 * @param canSend
	 * @return
	 */
	public long timeNextUrgent(boolean canSend) {
		long ret = Long.MAX_VALUE;
		if(canSend) {
			synchronized(this) {
				for(HashMap<Integer, MessageWrapper> started : startedByPrio) {
					for(MessageWrapper wrapper : started.values()) {
						if(wrapper.allSent()) continue;
						// We do not reset the deadline when we resend.
						long d = wrapper.getItem().getDeadline();
						if(d > 0)
							ret = Math.min(ret, d);
						else
							Logger.error(this, "Started sending message "+wrapper.getItem()+" but deadline is "+d);
					}
				}
			}
		}
		return ret;
	}
	
	public List<MessageItem> onDisconnect() {
		int messageSize = 0;
		List<MessageItem> items = null;
		// LOCKING: No packet may be sent while connected = false.
		// So we guarantee that no more packets are sent by setting this here.
		synchronized(this) {
			for(HashMap<Integer, MessageWrapper> queue : startedByPrio) {
				if(items == null)
					items = new ArrayList<MessageItem>();
				for(MessageWrapper wrapper : queue.values()) {
					items.add(wrapper.getItem());
					messageSize += wrapper.getLength();
				}
				queue.clear();
			}
			sendBufferUsed -= messageSize;
			// This is just a check for logging/debugging purposes.
			if(sendBufferUsed != 0) {
				Logger.warning(this, "Possible leak in transport code: Buffer size not empty after disconnecting on "+this+" for "+pn+" after removing "+messageSize+" total was "+sendBufferUsed);
				sendBufferUsed = 0;
			}
		}
		return items;
	}
	
	public int countSendableMessages() {
		int x = 0;
		synchronized(this) {
			for(HashMap<Integer, MessageWrapper> started : startedByPrio) {
				for(MessageWrapper wrapper : started.values()) {
					if(!wrapper.allSent()) x++;
				}
			}
		}
		return x;
	}
	
	public int getSendBufferSize() {
		synchronized(this) {
			return sendBufferUsed;
		}
	}
	
	public int getReceiveBufferSize() {
		synchronized(this) {
			return receiveBufferUsed;
		}
	}
	
	public boolean receiveBufferHalfFull() {
		return (getReceiveBufferSize() > MAX_RECEIVE_BUFFER_SIZE / 2);
	}
	
	public synchronized void acked(MessageWrapper wrapper, NewPacketFormat npf) {
		HashMap<Integer, MessageWrapper> started = startedByPrio.get(wrapper.getPriority());
		MessageWrapper removed = started.remove(wrapper.getMessageID());
		if(removed != null) {
			int size = wrapper.getLength();
			sendBufferUsed -= size;
			if(logDEBUG) Logger.debug(this, "Removed " + size + " from remote buffer. Total is now " + sendBufferUsed);
		}
		if(removed == null && logMINOR) {
			// npf.ack() can return true more than once, it just only calls the callbacks once.
			Logger.minor(this, "Completed message "+wrapper.getMessageID()+" but it is not in the map from "+wrapper);
		}

		if(removed != null) {
			if(logDEBUG) Logger.debug(this, "Completed message "+wrapper.getMessageID()+" from "+wrapper);

			boolean couldSend = canSend();
			int id = wrapper.getMessageID();
			ackedMessages.add(id, id);

			int oldWindow = messageWindowPtrAcked;
			while(ackedMessages.contains(messageWindowPtrAcked, messageWindowPtrAcked)) {
				messageWindowPtrAcked++;
				if(messageWindowPtrAcked == NUM_MESSAGE_IDS) messageWindowPtrAcked = 0;
			}

			if(messageWindowPtrAcked < oldWindow) {
				ackedMessages.remove(oldWindow, NUM_MESSAGE_IDS - 1);
				ackedMessages.remove(0, messageWindowPtrAcked);
			} else {
				ackedMessages.remove(oldWindow, messageWindowPtrAcked);
			}
			if(!couldSend && canSend()) {
				//We aren't blocked anymore, notify packet sender
				pn.wakeUpSender();
			}
		}
	}
}
