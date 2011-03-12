package freenet.node;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import freenet.io.xfer.PacketThrottle;
import freenet.node.NewPacketFormat.SentPacket;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SentTimes;
import freenet.support.Logger.LogLevel;

/** NewPacketFormat's context for each SessionKey. Specifically, packet numbers are unique
 * to a SessionKey, because the packet number is used in encrypting the packet. Hence this
 * class has everything to do with packet numbers - which to use next, which we've sent
 * packets on and are waiting for acks, which we've received and should ack etc.
 * @author toad
 */
public class NewPacketFormatKeyContext {

	public int firstSeqNumUsed = -1;
	public int nextSeqNum;
	public int highestReceivedSeqNum;

	public byte[][] seqNumWatchList = null;
	/** Index of the packet with the lowest sequence number */
	public int watchListPointer = 0;
	public int watchListOffset = 0;
	
	private final TreeMap<Integer, Long> acks = new TreeMap<Integer, Long>();
	private final HashMap<Integer, SentPacket> sentPackets = new HashMap<Integer, SentPacket>();
	/** Keep this many sent times even if the packets are not acked, so we can compute an
	 * accurate round trip time if they are acked after we had decided they were lost. */
	private static final int MAX_SENT_TIMES = 16384;
	/** This must be memory efficient. Given we have one per peer, a TreeMap would be way
	 * too big. */
	private final SentTimes sentTimes = new SentTimes(MAX_SENT_TIMES);
	
	private final Object sequenceNumberLock = new Object();
	
	private static final int REKEY_THRESHOLD = 100;
	/** All acks must be sent within 200ms */
	static final int MAX_ACK_DELAY = 200;
	/** Minimum RTT for purposes of calculating whether to retransmit. 
	 * Must be greater than MAX_ACK_DELAY */
	private static final int MIN_RTT_FOR_RETRANSMIT = 250;
	
	private int maxSeenInFlight;
	
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

	NewPacketFormatKeyContext(int ourFirstSeqNum, int theirFirstSeqNum) {
		ourFirstSeqNum &= 0x7FFFFFFF;
		theirFirstSeqNum &= 0x7FFFFFFF;
		
		this.nextSeqNum = ourFirstSeqNum;
		this.watchListOffset = theirFirstSeqNum;
		
		this.highestReceivedSeqNum = theirFirstSeqNum - 1;
		if(this.highestReceivedSeqNum == -1) this.highestReceivedSeqNum = Integer.MAX_VALUE;
	}
	
	boolean canAllocateSeqNum() {
		synchronized(sequenceNumberLock) {
			return nextSeqNum != firstSeqNumUsed;
		}
	}

	int allocateSequenceNumber(BasePeerNode pn) {
		synchronized(sequenceNumberLock) {
			if(firstSeqNumUsed == -1) {
				firstSeqNumUsed = nextSeqNum;
				if(logMINOR) Logger.minor(this, "First seqnum used for " + this + " is " + firstSeqNumUsed);
			} else {
				if(nextSeqNum == firstSeqNumUsed) {
					Logger.error(this, "Blocked because we haven't rekeyed yet");
					pn.startRekeying();
					return -1;
				}
				
				if(firstSeqNumUsed > nextSeqNum) {
					if(firstSeqNumUsed - nextSeqNum < REKEY_THRESHOLD) pn.startRekeying();
				} else {
					if((NewPacketFormat.NUM_SEQNUMS - nextSeqNum) + firstSeqNumUsed < REKEY_THRESHOLD) pn.startRekeying();
				}
			}
			int seqNum = nextSeqNum++;
			if((nextSeqNum == NewPacketFormat.NUM_SEQNUMS) || (nextSeqNum < 0)) {
				nextSeqNum = 0;
			}
			return seqNum;
		}
	}

	/** One of our outgoing packets has been acknowledged.
	 * @return False if we have already acked the packet */
	public void ack(int ack, BasePeerNode pn, SessionKey key) {
		long rtt;
		int maxSize;
		boolean lostBeforeAcked = false;
		boolean validAck = false;
		synchronized(sentPackets) {
			if(logDEBUG) Logger.debug(this, "Acknowledging packet "+ack+" from "+pn);
			SentPacket sent = sentPackets.remove(ack);
			if(sent != null) {
				rtt = sent.acked(key);
				maxSize = (maxSeenInFlight * 2) + 10;
				sentTimes.removeTime(ack);
				validAck = true;
			} else {
				if(logDEBUG) Logger.debug(this, "Already acked or lost "+ack);
				lostBeforeAcked = true;
				long l = sentTimes.removeTime(ack);
				if(l < 0) {
					if(logDEBUG) Logger.debug(this, "No time for "+ack+" - maybe acked twice?");
					return;
				} else {
					rtt = System.currentTimeMillis() - l;
					maxSize = (maxSeenInFlight * 2) + 10;
				}
			}
		}
		
		int rt = (int) Math.min(rtt, Integer.MAX_VALUE);
		PacketThrottle throttle = null;
		if(pn != null) {
			pn.reportPing(rt);
			throttle = pn.getThrottle();
			if(validAck)
				pn.receivedAck(System.currentTimeMillis());
		}
		if(throttle != null) {
			throttle.setRoundTripTime(rt);
			if(!lostBeforeAcked)
				throttle.notifyOfPacketAcknowledged(maxSize);
		}
	}

	/** Queue an ack.
	 * @return -1 If the ack was already queued, or the total number queued.
	 */
	public int queueAck(int seqno) {
		synchronized(acks) {
			if(!acks.containsKey(seqno)) {
				acks.put(seqno, System.currentTimeMillis());
				return acks.size();
			} else return -1;
		}
	}

	public void sent(int sequenceNumber, int length) {
		synchronized(sentPackets) {
			SentPacket sentPacket = sentPackets.get(sequenceNumber);
			if(sentPacket != null) sentPacket.sent(length);
		}
	}

	class AddedAcks {
		/** Are there any urgent acks? */
		final boolean anyUrgentAcks;
		private final HashMap<Integer, Long> moved;
		
		public AddedAcks(boolean mustSend, HashMap<Integer, Long> moved) {
			this.anyUrgentAcks = mustSend;
			this.moved = moved;
		}

		public void abort() {
			synchronized(acks) {
				acks.putAll(moved);
			}
		}
	}
	
	/** Add as many acks as possible to the packet.
	 * @return True if there are any old acks i.e. acks that will force us to send a packet
	 * even if there isn't anything else in it. */
	public AddedAcks addAcks(NPFPacket packet, int maxPacketSize, long now) {
		boolean mustSend = false;
		HashMap<Integer, Long> moved = null;
		int numAcks = 0;
		synchronized(acks) {
			Iterator<Map.Entry<Integer, Long>> it = acks.entrySet().iterator();
			while (it.hasNext() && packet.getLength() < maxPacketSize) {
				Map.Entry<Integer, Long> entry = it.next();
				int ack = entry.getKey();
				// All acks must be sent within 200ms.
				if(entry.getValue() + MAX_ACK_DELAY < now)
					mustSend = true;
				if(logDEBUG) Logger.debug(this, "Trying to ack "+ack);
				if(!packet.addAck(ack)) {
					if(logDEBUG) Logger.debug(this, "Can't add ack "+ack);
					break;
				}
				if(moved == null) {
					// FIXME some more memory efficient representation, since this will normally be very small?
					moved = new HashMap<Integer, Long>();
				}
				moved.put(ack, entry.getValue());
				++numAcks;
				it.remove();
			}
		}
		if(numAcks == 0)
			return null;
		return new AddedAcks(mustSend, moved);
	}

	public int countSentPackets() {
		synchronized(sentPackets) {
			return sentPackets.size();
		}
	}

	public void sent(SentPacket sentPacket, int seqNum, int length) {
		synchronized(sentPackets) {
			if(!sentPacket.messages.isEmpty()) {
				sentTimes.add(seqNum, System.currentTimeMillis());
			}
			sentPacket.sent(length);
			sentPackets.put(seqNum, sentPacket);
			int inFlight = sentPackets.size();
			if(inFlight > maxSeenInFlight) {
				maxSeenInFlight = inFlight;
				if(logDEBUG) Logger.debug(this, "Max seen in flight new record: "+maxSeenInFlight+" for "+this);
			}
		}
	}

	public long timeCheckForLostPackets(double averageRTT) {
		long timeCheck = Long.MAX_VALUE;
		synchronized(sentPackets) {
			// Because MIN_RTT_FOR_RETRANSMIT > MAX_ACK_DELAY, and because averageRTT() includes the actual ack delay, we don't need to add it on here.
			double avgRtt = Math.max(MIN_RTT_FOR_RETRANSMIT, averageRTT);
			Iterator<Map.Entry<Integer, SentPacket>> it = sentPackets.entrySet().iterator();
			while(it.hasNext()) {
				Map.Entry<Integer, SentPacket> e = it.next();
				SentPacket s = e.getValue();
				long t = (long) (s.getSentTime() + (avgRtt + MAX_ACK_DELAY * 1.1));
				if(t < timeCheck) timeCheck = t;
			}
		}
		return timeCheck;
	}

	public void checkForLostPackets(double averageRTT, long curTime, BasePeerNode pn) {
		//Mark packets as lost
		int bigLostCount = 0;
		int count = 0;
		synchronized(sentPackets) {
			// Because MIN_RTT_FOR_RETRANSMIT > MAX_ACK_DELAY, and because averageRTT() includes the actual ack delay, we don't need to add it on here.
			double avgRtt = Math.max(MIN_RTT_FOR_RETRANSMIT, averageRTT);

			Iterator<Map.Entry<Integer, SentPacket>> it = sentPackets.entrySet().iterator();
			while(it.hasNext()) {
				Map.Entry<Integer, SentPacket> e = it.next();
				SentPacket s = e.getValue();
				if(s.getSentTime() < (curTime - (avgRtt + MAX_ACK_DELAY * 1.1))) {
					if(logMINOR) {
						Logger.minor(this, "Assuming packet " + e.getKey() + " has been lost. "
						                + "Delay " + (curTime - s.getSentTime()) + "ms, "
						                + "threshold " + (avgRtt + MAX_ACK_DELAY * 1.1) + "ms");
					}
					s.lost();
					it.remove();
					bigLostCount++;
				} else
					count++;
			}
			if(count > 0 && logMINOR)
				Logger.minor(this, ""+count+" packets in flight with threshold "+(avgRtt + MAX_ACK_DELAY * 1.1) + "ms");
		}
		if(bigLostCount != 0 && pn != null) {
			PacketThrottle throttle = pn.getThrottle();
			if(throttle != null) {
				for(int i=0;i<bigLostCount;i++) {
					throttle.notifyOfPacketLost();
				}
			}
			pn.backoffOnResend();
		}
	}

	public long timeCheckForAcks() {
		long ret = Long.MAX_VALUE;
		synchronized(acks) {
			for(Long l : acks.values()) {
				long timeout = l + MAX_ACK_DELAY;
				if(ret > timeout) ret = timeout;
			}
		}
		return ret;
	}

	public void disconnected() {
		synchronized(sentPackets) {
			Iterator<Map.Entry<Integer, SentPacket>> it = sentPackets.entrySet().iterator();
			while(it.hasNext()) {
				Map.Entry<Integer, SentPacket> e = it.next();
				SentPacket s = e.getValue();
				s.lost();
			}
			sentPackets.clear();
		}
	}

}
