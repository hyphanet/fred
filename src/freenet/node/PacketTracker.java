/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.DMT;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.PacketThrottle;
import freenet.support.DoublyLinkedList;
import freenet.support.IndexableUpdatableSortedLinkedListItem;
import freenet.support.LimitedRangeIntByteArrayMap;
import freenet.support.LimitedRangeIntByteArrayMapElement;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.ReceivedPacketNumbers;
import freenet.support.TimeUtil;
import freenet.support.UpdatableSortedLinkedListKilledException;
import freenet.support.UpdatableSortedLinkedListWithForeignIndex;
import freenet.support.WouldBlockException;
import freenet.support.DoublyLinkedList.Item;
import freenet.support.Logger.LogLevel;

/**
 * @author amphibian
 * 
 * Class to track retransmissions, acknowledgements, packet numbers, etc.
 * May be shared by more than one SessionKey (aka session key).
 */
public class PacketTracker {

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/** Parent PeerNode */
	public final PeerNode pn;
	/** Are we the secondary key? */
	private volatile boolean isDeprecated;
	/** Packets we have sent to the node, minus those that have
	 * been acknowledged. */
	private final LimitedRangeIntByteArrayMap sentPacketsContents;
	/** Serial numbers of packets that we want to acknowledge,
	 * and when they become urgent. We always add to the end,
	 * and we always remove from the beginning, so should always
	 * be consistent. */
	private final List<QueuedAck> ackQueue;
	/** Serial numbers of packets that we have forgotten. Usually
	 * when we have forgotten a packet it just means that it has 
	 * been shifted to another SessionKey because this one was
	 * deprecated; the messages will get through in the end.
	 */
	private final List<QueuedForgotten> forgottenQueue;
	/** The highest incoming serial number we have ever seen
	 * from the other side. Includes actual packets and resend
	 * requests (provided they are within range). */
	private int highestSeenIncomingSerialNumber;
	/** Serial numbers of packets we want to be resent by the
	 * other side to us, the time at which they become sendable,
	 * and the time at which they become urgent. In order of
	 * the latter. */
	private final UpdatableSortedLinkedListWithForeignIndex<QueuedResendRequest> resendRequestQueue;
	/** Serial numbers of packets we want to be acknowledged by
	 * the other side, the time at which they become sendable,
	 * and the time at which they become urgent. In order of
	 * the latter. */
	private final UpdatableSortedLinkedListWithForeignIndex<QueuedAckRequest> ackRequestQueue;
	/** Numbered packets that we need to send to the other side
	 * because they asked for them. Just contains the numbers. */
	private final HashSet<Integer> packetsToResend;
	/** Ranges of packet numbers we have received from the other
	 * side. */
	private final ReceivedPacketNumbers packetNumbersReceived;
	/** Counter to generate the next packet number */
	private int nextPacketNumber;
	final long createdTime;
	/** The time at which we last successfully decoded a packet. */
	private long timeLastDecodedPacket;
	/** Tracker ID. Must be positive. */
	final long trackerID;
	private boolean wasUsed;

	/** Everything is clear to start with */
	PacketTracker(PeerNode pn, int firstPacketNumber) {
		this(pn, firstPacketNumber, pn.node.random.nextLong() & Long.MAX_VALUE);
	}

	PacketTracker(PeerNode pn, int firstPacketNumber, long tid) {
		trackerID = tid;
		this.pn = pn;
		ackQueue = new LinkedList<QueuedAck>();
		forgottenQueue = new LinkedList<QueuedForgotten>();
		highestSeenIncomingSerialNumber = -1;
		// give some leeway
		sentPacketsContents = new LimitedRangeIntByteArrayMap(128);
		resendRequestQueue = new UpdatableSortedLinkedListWithForeignIndex<QueuedResendRequest>();
		ackRequestQueue = new UpdatableSortedLinkedListWithForeignIndex<QueuedAckRequest>();
		packetsToResend = new HashSet<Integer>();
		packetNumbersReceived = new ReceivedPacketNumbers(512);
		isDeprecated = false;
		nextPacketNumber = firstPacketNumber;
		createdTime = System.currentTimeMillis();
	}

	/**
	 * Set the deprecated flag to indicate that we are now
	 * no longer the primary key. And wake up any threads trying to lock
	 * a packet number; they can be sent with the new KT.
	 * 
	 * After this, new packets will not be sent. It will not be possible to allocate a new
	 * packet number. However, old resend requests etc may still be sent.
	 */
	public void deprecated() {
		if(logMINOR) Logger.minor(this, "Deprecated: "+this);
		isDeprecated = true;
		sentPacketsContents.interrupt();
	}

	/**
	 * @return The highest received incoming serial number.
	 */
	public int highestReceivedIncomingSeqNumber() {
		return this.highestSeenIncomingSerialNumber;
	}

	/**
	 * Received this packet??
	 */
	public boolean alreadyReceived(int seqNumber) {
		return packetNumbersReceived.contains(seqNumber);
	}

	/** toString() - don't leak the key unless asked to */
	@Override
	public String toString() {
		return super.toString() + " for " + pn.shortToString();
	}

	/**
	 * Queue an ack to be sent back to the node soon.
	 * @param seqNumber The number of the packet to be acked.
	 */
	public void queueAck(int seqNumber) {
		if(logMINOR)
			Logger.minor(this, "Queueing ack for " + seqNumber);
		QueuedAck qa = new QueuedAck(seqNumber);
		synchronized(ackQueue) {
			ackQueue.add(qa);
		}
		synchronized(this) {
			wasUsed = true;
		}
	// Will go urgent in 200ms
	}

	public void queueForgotten(int seqNumber) {
		queueForgotten(seqNumber, true);
	}

	public void queueForgotten(int seqNumber, boolean log) {
		if(log && ((!isDeprecated) || logMINOR)) {
			String msg = "Queueing forgotten for " + seqNumber + " for " + this;
			if(!isDeprecated) {
				Logger.error(this, msg);
			} else {
				Logger.minor(this, msg);
			}
		}
		QueuedForgotten qf = new QueuedForgotten(seqNumber);
		synchronized(forgottenQueue) {
			forgottenQueue.add(qf);
		}
	}

	static class PacketActionItem { // anyone got a better name?

		/** Packet sequence number */
		int packetNumber;
		/** Time at which this packet's ack or resend request becomes urgent
		 * and can trigger an otherwise empty packet to be sent. */
		long urgentTime;

		@Override
		public String toString() {
			return super.toString() + ": packet " + packetNumber + " urgent@" + urgentTime + '(' + (System.currentTimeMillis() - urgentTime) + ')';
		}
	}

	private final static class QueuedAck extends PacketActionItem {

		QueuedAck(int packet) {
			long now = System.currentTimeMillis();
			packetNumber = packet;
			/** If not included on a packet in next 200ms, then
			 * force a send of an otherwise empty packet.
			 */
			urgentTime = now + 200;
		}
	}

	// FIXME this is almost identical to QueuedAck, coalesce the classes
	private final static class QueuedForgotten extends PacketActionItem {

		QueuedForgotten(int packet) {
			long now = System.currentTimeMillis();
			packetNumber = packet;
			urgentTime = now + PacketSender.MAX_COALESCING_DELAY;
		}
	}

	private abstract class BaseQueuedResend<T extends BaseQueuedResend<T>> extends PacketActionItem
		implements IndexableUpdatableSortedLinkedListItem<T> {

		/** Time at which this item becomes sendable.
		 * When we send a resend request, this is reset to t+500ms.
		 * 
		 * Constraint: urgentTime is always greater than activeTime.
		 */
		long activeTime;

		void sent() throws UpdatableSortedLinkedListKilledException {
			long now = System.currentTimeMillis();
			activeTime = now + 500;
			urgentTime = activeTime + urgentDelay();
		// This is only removed when we actually receive the packet
		// But for now it will sleep
		}

		BaseQueuedResend(int packetNumber) {
			this.packetNumber = packetNumber;
			long now = System.currentTimeMillis();
			activeTime = initialActiveTime(now);
			urgentTime = activeTime + urgentDelay();
		}

		abstract long urgentDelay();

		abstract long initialActiveTime(long now);
		private T next;
		private T prev;

		public final T getNext() {
			return next;
		}

		@SuppressWarnings("unchecked")
		public final T setNext(Item<?> i) {
			T old = next;
			next = (T)i;
			return old;
		}

		public T getPrev() {
			return prev;
		}

		@SuppressWarnings("unchecked")
		public T setPrev(Item<?> i) {
			T old = prev;
			prev = (T)i;
			return old;
		}

		public int compareTo(T r) {
			if(urgentTime > r.urgentTime)
				return 1;
			if(urgentTime < r.urgentTime)
				return -1;
			if(packetNumber > r.packetNumber)
				return 1;
			if(packetNumber < r.packetNumber)
				return -1;
			return 0;
		}

		public Object indexValue() {
			return packetNumber;
		}
		private DoublyLinkedList<? super T> parent;

		public DoublyLinkedList<? super T> getParent() {
			return parent;
		}

		public DoublyLinkedList<? super T> setParent(DoublyLinkedList<? super T> l) {
			DoublyLinkedList<? super T> old = parent;
			parent = l;
			return old;
		}
	}

	private class QueuedResendRequest extends BaseQueuedResend<QueuedResendRequest> {

		@Override
		long initialActiveTime( long now) {
			return now; // Active immediately; reordering is rare
		}

		QueuedResendRequest(int packetNumber) {
			super(packetNumber);
		}

		@Override
		void sent() throws UpdatableSortedLinkedListKilledException {
			synchronized(resendRequestQueue) {
				super.sent();
				resendRequestQueue.update(this);
			}
		}

		@Override
		long urgentDelay() {
			return PacketSender.MAX_COALESCING_DELAY; // Urgent pretty soon
		}
	}

	private class QueuedAckRequest extends BaseQueuedResend<QueuedAckRequest> {

		final long createdTime;
		long activeDelay;

		@Override
		long initialActiveTime( long now) {
			// Request an ack after four RTTs
			activeDelay = twoRTTs();
			return now + activeDelay;
		}

		QueuedAckRequest(int packetNumber) {
			super(packetNumber);
			this.createdTime = System.currentTimeMillis();
		}

		@Override
		void sent() throws UpdatableSortedLinkedListKilledException {
			synchronized(ackRequestQueue) {
				super.sent();
				ackRequestQueue.update(this);
			}
		}

		/**
		 * Acknowledged.
		 */
		public void onAcked() {
			long t = Math.max(0, System.currentTimeMillis() - createdTime);
			pn.reportPing(t);
			if(logMINOR)
				Logger.minor(this, "Reported round-trip time of " + TimeUtil.formatTime(t, 2, true) + " on " + pn.getPeer() + " (avg " + TimeUtil.formatTime((long) pn.averagePingTime(), 2, true) + ", #" + packetNumber + ')');
		}

		@Override
		long urgentDelay() {
			return PacketSender.MAX_COALESCING_DELAY;
		}
	}

	/**
	 * Called when we receive a packet.
	 * @param seqNumber The packet's serial number.
	 * See the comments in FNPPacketMangler.processOutgoing* for
	 * the reason for the locking.
	 */
	public synchronized void receivedPacket(int seqNumber) {
		timeLastDecodedPacket = System.currentTimeMillis();
		if(logMINOR)
			Logger.minor(this, "Received packet " + seqNumber + " from " + pn.shortToString());
		if(seqNumber == -1)
			return;
		// Received packet
		receivedPacketNumber(seqNumber);
		// Ack it even if it is a resend
		queueAck(seqNumber);
	}

	// TCP uses four RTTs with no ack to resend ... but we have a more drawn out protocol, we
	// should use only two.
	public long twoRTTs() {
		// FIXME upper bound necessary ?
		return (long) Math.min(Math.max(250, pn.averagePingTime() * 2), 2500);
	}

	protected void receivedPacketNumber(int seqNumber) {
		if(logMINOR)
			Logger.minor(this, "Handling received packet number " + seqNumber);
		queueResendRequests(seqNumber);
		packetNumbersReceived.got(seqNumber);
		try {
			removeResendRequest(seqNumber);
		} catch(UpdatableSortedLinkedListKilledException e) {
			// Ignore, not our problem
		}
		synchronized(this) {
			highestSeenIncomingSerialNumber = Math.max(highestSeenIncomingSerialNumber, seqNumber);
			wasUsed = true;
		}
		if(logMINOR)
			Logger.minor(this, "Handled received packet number " + seqNumber);
	}

	/**
	 * Remove a resend request from the queue.
	 * @param seqNumber
	 * @throws UpdatableSortedLinkedListKilledException 
	 */
	private void removeResendRequest(int seqNumber) throws UpdatableSortedLinkedListKilledException {
		synchronized(resendRequestQueue) {
			resendRequestQueue.removeByKey(seqNumber);
		}
	}

	/**
	 * Add some resend requests if necessary.
	 * @param seqNumber The number of the packet we just received.
	 */
	private void queueResendRequests(int seqNumber) {
		int max;
		synchronized(this) {
			max = packetNumbersReceived.highest();
		}
		if(seqNumber > max)
			try {
				if((max != -1) && (seqNumber - max > 1)) {
					if(logMINOR)
						Logger.minor(this, "Queueing resends from " + max + " to " + seqNumber);
					// Missed some packets out
					for(int i = max + 1; i < seqNumber; i++) {
						queueResendRequest(i);
					}
				}
			} catch(UpdatableSortedLinkedListKilledException e) {
				// Ignore (we are decoding packet, not sending one)
			}
	}

	/**
	 * Queue a resend request
	 * @param packetNumber the packet serial number to queue a
	 * resend request for
	 * @throws UpdatableSortedLinkedListKilledException 
	 */
	private void queueResendRequest(int packetNumber) throws UpdatableSortedLinkedListKilledException {
		synchronized(resendRequestQueue) {
			if(queuedResendRequest(packetNumber)) {
				if(logMINOR)
					Logger.minor(this, "Not queueing resend request for " + packetNumber + " - already queued");
				return;
			}
			if(logMINOR)
				Logger.minor(this, "Queueing resend request for " + packetNumber);
			QueuedResendRequest qrr = new QueuedResendRequest(packetNumber);
			resendRequestQueue.add(qrr);
		}
	}

	/**
	 * Queue an ack request
	 * @param packetNumber the packet serial number to queue a
	 * resend request for
	 * @throws UpdatableSortedLinkedListKilledException 
	 */
	private void queueAckRequest(int packetNumber) throws UpdatableSortedLinkedListKilledException {
		synchronized(ackRequestQueue) {
			// FIXME should we just remove the existing ack request? If we do, we get a better
			// estimate of RTT on lossy links... if we don't, lossy links will include the average
			// time to send a packet including all resends. The latter may be useful, and in fact
			// the former is unreliable...
			if(queuedAckRequest(packetNumber)) {
				if(logMINOR)
					Logger.minor(this, "Not queueing ack request for " + packetNumber + " - already queued");
				return;
			}
			if(logMINOR)
				Logger.minor(this, "Queueing ack request for " + packetNumber + " on " + this);
			QueuedAckRequest qrr = new QueuedAckRequest(packetNumber);
			ackRequestQueue.add(qrr);
		}
	}

	/**
	 * Is an ack request queued for this packet number?
	 */
	private boolean queuedAckRequest(int packetNumber) {
		synchronized(ackRequestQueue) {
			return ackRequestQueue.containsKey(packetNumber);
		}
	}

	/**
	 * Is a resend request queued for this packet number?
	 */
	private boolean queuedResendRequest(int packetNumber) {
		synchronized(resendRequestQueue) {
			return resendRequestQueue.containsKey(packetNumber);
		}
	}

	/**
	 * Called when we have received several packet acknowledgements.
	 * Synchronized for the same reason as the sender code is:
	 * So that we don't end up sending packets too late when overloaded,
	 * and get horrible problems such as asking to resend packets which
	 * haven't been sent yet.
	 * @return True if there were any valid acks (acks for packets that haven't
	 * already been acked).
	 */
	public synchronized boolean acknowledgedPackets(int[] seqNos) {
		boolean validAck = false;
		// FIXME locking: can we just sync on the first part, and not the callbacks? 
		// acknowledgedPacket() only sync's on removeAckRequest, but as mentioned above we need to do a bit more...
		AsyncMessageCallback[][] callbacks = new AsyncMessageCallback[seqNos.length][];
		for(int i = 0; i < seqNos.length; i++) {
			int realSeqNo = seqNos[i];
			if(logMINOR)
				Logger.minor(this, "Acknowledged packet: " + realSeqNo);
			try {
				validAck |= removeAckRequest(realSeqNo);
			} catch(UpdatableSortedLinkedListKilledException e) {
				// Ignore, we are processing an incoming packet
			}
			if(logMINOR)
				Logger.minor(this, "Removed ack request");
			callbacks[i] = sentPacketsContents.getCallbacks(realSeqNo);
			byte[] buf = sentPacketsContents.get(realSeqNo);
			long timeAdded = sentPacketsContents.getTime(realSeqNo);
			if(sentPacketsContents.remove(realSeqNo)) {
				validAck = true;
				if(buf.length > Node.PACKET_SIZE) {
					PacketThrottle throttle = pn.getThrottle();
					throttle.notifyOfPacketAcknowledged(1024);
					throttle.setRoundTripTime(System.currentTimeMillis() - timeAdded);
				}
			}
			if(logMINOR)
				Logger.minor(this, "Removed ack request, callbacks "+(callbacks[i] == null ? "(null)" : callbacks[i].length));
		}
		int cbCount = 0;
		for(int i = 0; i < callbacks.length; i++) {
			AsyncMessageCallback[] cbs = callbacks[i];
			if(cbs != null)
				for(int j = 0; j < cbs.length; j++) {
					cbs[j].acknowledged();
					cbCount++;
				}
		}
		if(logMINOR)
			Logger.minor(this, "Executed " + cbCount + " callbacks");
		try {
			wouldBlock(true);
		} catch(BlockedTooLongException e) {
			// Ignore, will come up again. In any case it's rather unlikely...
		}
		return validAck;
	}

	/**
	 * Called when we have received a packet acknowledgement.
	 * @param realSeqNo
	 */
	public boolean acknowledgedPacket(int realSeqNo) {
		boolean validAck = false;
		AsyncMessageCallback[] callbacks;
		if(logMINOR)
			Logger.minor(this, "Acknowledged packet: " + realSeqNo);
		try {
			synchronized(this) {
				validAck |= removeAckRequest(realSeqNo);
			}
		} catch(UpdatableSortedLinkedListKilledException e) {
			// Ignore, we are processing an incoming packet
		}
		callbacks = sentPacketsContents.getCallbacks(realSeqNo);
		if(logMINOR)
			Logger.minor(this, "Removed ack request, callbacks "+(callbacks == null ? "(null)" : callbacks.length));
		byte[] buf = sentPacketsContents.get(realSeqNo);
		long timeAdded = sentPacketsContents.getTime(realSeqNo);
		if(sentPacketsContents.remove(realSeqNo)) {
			validAck = true;
			if(buf.length > Node.PACKET_SIZE) {
				PacketThrottle throttle = pn.getThrottle();
				throttle.notifyOfPacketAcknowledged(1024);
				throttle.setRoundTripTime(System.currentTimeMillis() - timeAdded);
			}
		}
		try {
			wouldBlock(true);
		} catch(BlockedTooLongException e) {
			// Ignore, will come up again. In any case it's rather unlikely...
		}
		if(callbacks != null) {
			for(int i = 0; i < callbacks.length; i++)
				callbacks[i].acknowledged();
			if(logMINOR)
				Logger.minor(this, "Executed " + callbacks.length + " callbacks");
		}
		return validAck;
	}

	/**
	 * Remove an ack request from the queue by packet number.
	 * @throws UpdatableSortedLinkedListKilledException 
	 * @return True if a packet was acked that has not previously been acked.
	 */
	private boolean removeAckRequest(int seqNo) throws UpdatableSortedLinkedListKilledException {
		QueuedAckRequest qr = null;

		synchronized(ackRequestQueue) {
			qr = ackRequestQueue.removeByKey(seqNo);
		}
		if(qr != null) {
			qr.onAcked();
			return true;
		} else {
			Logger.normal(this, "Removing ack request twice? Null on " + seqNo + " from " + pn.getPeer() + " (" + TimeUtil.formatTime((int) pn.averagePingTime(), 2, true) + " ping avg)");
			return false;
		}
	}

	/**
	 * Resend (off-thread but ASAP) a packet.
	 * @param seqNumber The serial number of the packet to be
	 * resent.
	 */
	public void resendPacket(int seqNumber) {
		byte[] resendData = sentPacketsContents.get(seqNumber);
		if(resendData != null) {
			if(resendData.length > Node.PACKET_SIZE)
				pn.getThrottle().notifyOfPacketLost();
			synchronized(packetsToResend) {
				packetsToResend.add(seqNumber);
			}
			pn.node.ps.wakeUp();
		} else {
			synchronized(this) {
				if(nextPacketNumber <= seqNumber) {
					Logger.error(this, "Asking me to resend packet "+seqNumber+" which I haven't sent yet (next="+nextPacketNumber+") on "+this);
					// FIXME forceDisconnect when sure this won't be catastrophic
					return;
				} else {
					Logger.error(this, "Asking me to resend packet "+seqNumber+" which has already been acked or we skipped the packet number (next="+nextPacketNumber+") on "+this+" - will tell other side we have forgotten it");
					// Can't resend it. Tell other side we forgot it.
				}
			}
			queueForgotten(seqNumber); // Happens to be true... or maybe it didn't exist in the first place?
		}
	}

	/**
	 * Called when we receive an AckRequest.
	 * @param packetNumber The packet that the other side wants
	 * us to re-ack.
	 */
	public synchronized void receivedAckRequest(int packetNumber) {
		if(queuedAck(packetNumber)) {
			// Already going to send an ack
			// Don't speed it up though; wasteful
		} else if(packetNumbersReceived.contains(packetNumber))
			// We have received it, so send them an ack
			queueAck(packetNumber);
		else {
			// We have not received it, so get them to resend it
			try {
				queueResendRequest(packetNumber);
			} catch(UpdatableSortedLinkedListKilledException e) {
				// Ignore, we are decoding, not sending.
				}
			highestSeenIncomingSerialNumber = Math.max(highestSeenIncomingSerialNumber, packetNumber);
		}
	}

	/**
	 * Is there a queued ack with the given packet number?
	 * FIXME: have a hashtable? The others do, but probably it
	 * isn't necessary. We should be consistent about this -
	 * either take it out of UpdatableSortedLinkedListWithForeignIndex,
	 * or add one here.
	 */
	private boolean queuedAck(int packetNumber) {
		synchronized(ackQueue) {
			for(QueuedAck qa : ackQueue) {
				if(qa.packetNumber == packetNumber)
					return true;
			}
		}
		return false;
	}

	/**
	 * Destination forgot a packet.
	 * This is normal if we are the secondary key.
	 * @param seqNumber The packet number lost.
	 */
	public void destForgotPacket(int seqNumber) {
		if(isDeprecated)
			Logger.normal(this, "Destination forgot packet: " + seqNumber);
		else
			Logger.error(this, "Destination forgot packet: " + seqNumber);
		synchronized(this) {
			try {
				removeResendRequest(seqNumber);
			} catch(UpdatableSortedLinkedListKilledException e) {
				// Ignore
			}
		}
	}

	private long timeWouldBlock = -1;
	static final long MAX_WOULD_BLOCK_DELTA = 10 * 60 * 1000;

	public boolean wouldBlock(boolean wakeTicker) throws BlockedTooLongException {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(sentPacketsContents.wouldBlock(nextPacketNumber)) {
				if(timeWouldBlock == -1)
					timeWouldBlock = now;
				else {
					long delta = now - timeWouldBlock;
					if(delta > MAX_WOULD_BLOCK_DELTA) {
						Logger.error(this, "Not been able to allocate a packet to tracker " + this + " for " + TimeUtil.formatTime(delta, 3, true));
						throw new BlockedTooLongException(this, delta);
					}
				}
				return true;
			} else
				if(timeWouldBlock != -1) {
					long delta = now - timeWouldBlock;
					timeWouldBlock = -1;
					if(delta > PacketSender.MAX_COALESCING_DELAY)
						Logger.error(this, "Waking PacketSender: have been blocking for packet ack for " + TimeUtil.formatTime(delta, 3, true));
					else
						return false;
				} else
					return false;
		}
		pn.node.ps.wakeUp();
		return false;
	}

	/**
	 * @return A packet number for a new outgoing packet.
	 * This method will not block, and will throw an exception
	 * if it would need to block.
	 * @throws KeyChangedException if the thread is interrupted when waiting
	 */
	public int allocateOutgoingPacketNumberNeverBlock() throws KeyChangedException, NotConnectedException, WouldBlockException {
		int packetNumber;
		if(!pn.isConnected())
			throw new NotConnectedException();
		synchronized(this) {
			wasUsed = true;
			packetNumber = nextPacketNumber;
			if(isDeprecated)
				throw new KeyChangedException();
			sentPacketsContents.lockNeverBlock(packetNumber);
			timeWouldBlock = -1;
			nextPacketNumber = packetNumber + 1;
			if(logMINOR)
				Logger.minor(this, "Allocated " + packetNumber + " in allocateOutgoingPacketNumberNeverBlock for " + this);
			return packetNumber;
		}
	}

	public int[] grabForgotten() {
		if(logMINOR)
			Logger.minor(this, "Grabbing forgotten packet numbers");
		int[] acks;
		synchronized(forgottenQueue) {
			// Grab the acks and tell them they are sent
			int length = forgottenQueue.size();
			acks = new int[length];
			int i = 0;

			Iterator<QueuedForgotten> it = forgottenQueue.iterator();
			while(it.hasNext()) {
				QueuedForgotten ack = it.next();
				acks[i++] = ack.packetNumber;
				if(logMINOR)
					Logger.minor(this, "Grabbing ack " + ack.packetNumber + " from " + this);
				it.remove();	// sent
			}
		}
		return acks;
	}

	public void requeueForgot(int[] forgotPackets, int start, int length) {
		synchronized(forgottenQueue) { // It doesn't do anything else does it? REDFLAG
			for(int i = start; i < start + length; i++) {
				queueForgotten(i, false);
			}
		}
	}

	/**
	 * Grab all the currently queued acks to be sent to this node.
	 * @return An array of packet numbers that we need to acknowledge.
	 */
	public int[] grabAcks() {
		if(logMINOR)
			Logger.minor(this, "Grabbing acks");
		int[] acks;
		synchronized(ackQueue) {
			// Grab the acks and tell them they are sent
			int length = ackQueue.size();
			acks = new int[length];
			int i = 0;
			Iterator<QueuedAck> it = ackQueue.iterator();
			while(it.hasNext()) {
				QueuedAck ack = it.next();
				acks[i++] = ack.packetNumber;
				if(logMINOR)
					Logger.minor(this, "Grabbing ack " + ack.packetNumber + " from " + this);
			}
			ackQueue.clear();
		}
		return acks;
	}

	/**
	 * Grab all the currently queued resend requests.
	 * @return An array of the packet numbers of all the packets we want to be resent.
	 * @throws NotConnectedException If the peer is no longer connected.
	 */
	public int[] grabResendRequests() throws NotConnectedException {
		QueuedResendRequest[] items;
		int[] packetNumbers;
		int realLength;
		long now = System.currentTimeMillis();
		try {
			synchronized(resendRequestQueue) {
				items = resendRequestQueue.toArray(new QueuedResendRequest[resendRequestQueue.size()]);
				int length = items.length;
				packetNumbers = new int[length];
				realLength = 0;
				for(int i = 0; i < length; i++) {
					QueuedResendRequest qrr = items[i];
					if(packetNumbersReceived.contains(qrr.packetNumber)) {
						if(logMINOR)
							Logger.minor(this, "Have already seen " + qrr.packetNumber + ", removing from resend list");
						resendRequestQueue.remove(qrr);
						continue;
					}
					if(qrr.activeTime <= now) {
						packetNumbers[realLength++] = qrr.packetNumber;
						if(logMINOR)
							Logger.minor(this, "Grabbing resend request: " + qrr.packetNumber + " from " + this);
						qrr.sent();
					} else if(logMINOR)
						Logger.minor(this, "Rejecting resend request: " + qrr.packetNumber + " - in future by " + (qrr.activeTime - now) + "ms for " + this);
				}
			}
		} catch(UpdatableSortedLinkedListKilledException e) {
			throw new NotConnectedException();
		}
		int[] trimmedPacketNumbers = new int[realLength];
		System.arraycopy(packetNumbers, 0, trimmedPacketNumbers, 0, realLength);
		return trimmedPacketNumbers;
	}

	public int[] grabAckRequests() throws NotConnectedException, StillNotAckedException {
		QueuedAckRequest[] items;
		int[] packetNumbers;
		int realLength;
		if(logMINOR)
			Logger.minor(this, "Grabbing ack requests");
		try {
			synchronized(ackRequestQueue) {
				long now = System.currentTimeMillis();
				items = ackRequestQueue.toArray(new QueuedAckRequest[ackRequestQueue.size()]);
				int length = items.length;
				packetNumbers = new int[length];
				realLength = 0;
				for(int i = 0; i < length; i++) {
					QueuedAckRequest qr = items[i];
					int packetNumber = qr.packetNumber;
					if(qr.activeTime <= now) {
						if(sentPacketsContents.get(packetNumber) == null) {
							if(logMINOR)
								Logger.minor(this, "Asking to ack packet which has already been acked: " + packetNumber + " on " + this + ".grabAckRequests");
							ackRequestQueue.remove(qr);
							continue;
						}
						if(now - qr.createdTime > 2 * 60 * 1000) {
							if(logMINOR)
								Logger.minor(this, "Packet " + qr.packetNumber + " sent over " + (now - qr.createdTime) + "ms ago and still not acked on " + this + " for " + pn);
							if(now - qr.createdTime > 10 * 60 * 1000) {
								Logger.error(this, "Packet " + qr.packetNumber + " sent over " + (now - qr.createdTime) + "ms ago and still not acked on " + this + " for " + pn);
								throw new StillNotAckedException(this);
							}
						}
						packetNumbers[realLength++] = packetNumber;
						if(logMINOR)
							Logger.minor(this, "Grabbing ack request " + packetNumber + " (" + realLength + ") from " + this);
						qr.sent();
					} else if(logMINOR)
						Logger.minor(this, "Ignoring ack request " + packetNumber + " (" + realLength + ") - will become active in " + (qr.activeTime - now) + "ms on " + this + " - " + qr);
				}
			}
		} catch(UpdatableSortedLinkedListKilledException e) {
			throw new NotConnectedException();
		}
		if(logMINOR)
			Logger.minor(this, "realLength now " + realLength);
		int[] trimmedPacketNumbers = new int[realLength];
		System.arraycopy(packetNumbers, 0, trimmedPacketNumbers, 0, realLength);
		if(logMINOR)
			Logger.minor(this, "Returning " + trimmedPacketNumbers.length + " ackRequests");
		return trimmedPacketNumbers;
	}

	/**
	 * @return The time at which we will have to send some
	 * notifications. Or Long.MAX_VALUE if there are none to send.
	 */
	public long getNextUrgentTime() {
		long earliestTime = Long.MAX_VALUE;
		synchronized(ackQueue) {
			if(!ackQueue.isEmpty()) {
				QueuedAck qa = ackQueue.get(0);
				earliestTime = qa.urgentTime;
			}
		}
		PacketActionItem qr = null;
		synchronized(resendRequestQueue) {
			if(!resendRequestQueue.isEmpty())
				qr = resendRequestQueue.getLowest();
		}
		if (qr != null)
			earliestTime = Math.min(earliestTime, qr.urgentTime);

		synchronized(ackRequestQueue) {
			if(!ackRequestQueue.isEmpty())
				qr = ackRequestQueue.getLowest();
		}
		if (qr != null)
			earliestTime = Math.min(earliestTime, qr.urgentTime);
		return earliestTime;
	}

	public long timeSendAcks() {
		long earliestTime = Long.MAX_VALUE;
		synchronized(ackQueue) {
			if(!ackQueue.isEmpty()) {
				QueuedAck qa = ackQueue.get(0);
				earliestTime = qa.urgentTime;
			}
		}
		return earliestTime;
	}
	
	/**
	 * @return The last sent new packet number.
	 */
	public int getLastOutgoingSeqNumber() {
		synchronized(this) {
			return nextPacketNumber - 1;
		}
	}

	/**
	 * Report a packet has been sent
	 * @param data The data we have just sent (payload only, decrypted). 
	 * @param seqNumber The packet number.
	 * @throws NotConnectedException 
	 */
	public void sentPacket(byte[] data, int seqNumber, AsyncMessageCallback[] callbacks, short priority) throws NotConnectedException {
		if(logMINOR) Logger.minor(this, "Sent packet "+seqNumber+" length "+data.length+" with "+(callbacks == null ? "no callbacks" : callbacks.length));
		if(callbacks != null)
			for(int i = 0; i < callbacks.length; i++) {
				if(callbacks[i] == null)
					throw new NullPointerException();
			}
		if(!sentPacketsContents.add(seqNumber, data, callbacks, priority))
			throw new IllegalStateException("Cannot add data for packet "+seqNumber);
		try {
			queueAckRequest(seqNumber);
		} catch(UpdatableSortedLinkedListKilledException e) {
			throw new NotConnectedException();
		}
	}

	/**
	 * Clear the SessionKey. Deprecate it, clear all resend, ack, request-ack etc queues.
	 * Return the messages we still had in flight. The caller will then either add them to
	 * another SessionKey, or call their callbacks to indicate failure.
	 */
	private LimitedRangeIntByteArrayMapElement[] clear() {
		if(logMINOR)
			Logger.minor(this, "Clearing " + this);
		isDeprecated = true;
		LimitedRangeIntByteArrayMapElement[] elements;
		synchronized(sentPacketsContents) {
			elements = sentPacketsContents.grabAll(); // will clear
		}
		synchronized(ackQueue) {
			ackQueue.clear();
		}
		synchronized(resendRequestQueue) {
			resendRequestQueue.kill();
		}
		synchronized(ackRequestQueue) {
			ackRequestQueue.kill();
		}
		synchronized(packetsToResend) {
			packetsToResend.clear();
		}
		packetNumbersReceived.clear();
		return elements;
	}

	/**
	 * Completely deprecate the SessionKey, in favour of a new one. 
	 * It will no longer be used for anything. The SessionKey will be cleared and all outstanding packets
	 * moved to the new SessionKey.
	 * 
	 * *** Must only be called if the SessionKey is not to be kept. Otherwise, we may receive some packets twice. ***
	 */
	public void completelyDeprecated(SessionKey newTracker) {
		if(newTracker.packets == this) {
			Logger.error(this, "Completely deprecated in favour of self!", new Exception("debug"));
			return;
		}
		if(logMINOR)
			Logger.minor(this, "Completely deprecated: " + this + " in favour of " + newTracker);
		LimitedRangeIntByteArrayMapElement[] elements = clear();
		if(elements.length == 0)
			return; // nothing more to do
		MessageItem[] messages = new MessageItem[elements.length];
		for(int i = 0; i < elements.length; i++) {
			LimitedRangeIntByteArrayMapElement element = elements[i];
			byte[] buf = element.data;
			AsyncMessageCallback[] callbacks = element.callbacks;
			// Ignore packet#
			if(logMINOR)
				Logger.minor(this, "Queueing resend of what was once " + element.packetNumber);
			messages[i] = new MessageItem(buf, callbacks, true, pn.resendByteCounter, element.priority, false, false);
		}
		pn.requeueMessageItems(messages, 0, messages.length, true);

		pn.node.ps.wakeUp();
	}

	/**
	 * Called when the node appears to have been disconnected.
	 * Dump all sent messages.
	 */
	public void disconnected() {
		// Clear everything, call the callbacks
		LimitedRangeIntByteArrayMapElement[] elements = clear();
		for(int i = 0; i < elements.length; i++) {
			LimitedRangeIntByteArrayMapElement element = elements[i];
			AsyncMessageCallback[] callbacks = element.callbacks;
			if(callbacks != null)
				for(int j = 0; j < callbacks.length; j++)
					callbacks[j].disconnected();
		}
	}

	/**
	 * Fill rpiTemp with ResendPacketItems of packets that need to be
	 * resent.
	 * @return An array of integers which contains the packet numbers
	 * to be resent (the RPI's are put into rpiTemp), or null if there
	 * are no packets to resend.
	 * 
	 * Not a very nice API, but it saves a load of allocations, and at
	 * least it's documented!
	 */
	public int[] grabResendPackets(Vector<ResendPacketItem> rpiTemp, int[] numbers) {
		rpiTemp.clear();
		long now = System.currentTimeMillis();
		long fourRTTs = twoRTTs();
		int count = 0;
		synchronized(packetsToResend) {
			int len = packetsToResend.size();
			if(numbers.length < len)
				numbers = new int[len * 2];
			for(Iterator<Integer> it = packetsToResend.iterator(); it.hasNext();) {
				int packetNo = it.next();
				long resentTime = sentPacketsContents.getReaddedTime(packetNo);
				if(now - resentTime > fourRTTs) {
					// Either never resent, or resent at least 4 RTTs ago
					numbers[count++] = packetNo;
					it.remove();
				}
			}
			packetsToResend.clear();
		}
		for(int i = 0; i < count; i++) {
			int packetNo = numbers[i];
			byte[] buf = sentPacketsContents.get(packetNo);
			if(buf == null) {
				if(logMINOR)
					Logger.minor(this, "Contents null for " + packetNo + " in grabResendPackets on " + this);
				continue; // acked already?
			}
			AsyncMessageCallback[] callbacks = sentPacketsContents.getCallbacks(packetNo);
			short priority = sentPacketsContents.getPriority(packetNo, DMT.PRIORITY_BULK_DATA);
			rpiTemp.add(new ResendPacketItem(buf, packetNo, this, callbacks, priority));
		}
		if(rpiTemp.isEmpty())
			return null;
		return numbers;
	}

	public boolean hasPacketsToResend() {
		synchronized(packetsToResend) {
			return !packetsToResend.isEmpty();
		}
	}

	public boolean isDeprecated() {
		return this.isDeprecated;
	}

	public int countAckRequests() {
		synchronized(ackRequestQueue) {
			return ackRequestQueue.size();
		}
	}

	public int countResendRequests() {
		synchronized(resendRequestQueue) {
			return resendRequestQueue.size();
		}
	}

	public int countAcks() {
		synchronized(ackQueue) {
			return ackQueue.size();
		}
	}

	public synchronized long timeLastDecodedPacket() {
		return timeLastDecodedPacket;
	}

	public synchronized boolean wasUsed() {
		return wasUsed;
	}
}
