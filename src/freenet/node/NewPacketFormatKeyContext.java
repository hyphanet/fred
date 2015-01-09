package freenet.node;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import freenet.io.xfer.PacketThrottle;
import freenet.node.NewPacketFormat.SentPacket;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SentTimeCache;
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

	// WARNING: timeCheckForAcksCached must invalidated or adjusted if acks is altered
	private final TreeMap<Integer, Long> acks = new TreeMap<Integer, Long>();
	private long timeCheckForAcksCached = Long.MAX_VALUE; // cached timeCheckForAcks; Long.MIN_VALUE if invalid; Long.MAX_VALUE for empty acks (initial state);  uses "synchronized(acks)" lock

	// WARNING: minSentTimeCached must be invalidated or adjusted if any sentPackets added/removed/changed
	private final HashMap<Integer, SentPacket> sentPackets = new HashMap<Integer, SentPacket>();
	private long minSentTimeCached = Long.MAX_VALUE; // cached minimum time for sentPackets; Long.MIN_VALUE if invalid; Long.MAX_VALUE for empty sentPackets (initial state);  uses "synchronized(sentPackets)" lock

	/** Keep this many sent times for lost packets, so we can compute an accurate round trip time if
	 * they are acked after we had decided they were lost. */
	private static final int MAX_LOST_SENT_TIMES = 128;
	/** We add all lost packets sequence numbers and the corresponding sent time to this cache. */
	private final SentTimeCache lostSentTimes = new SentTimeCache(MAX_LOST_SENT_TIMES);
	
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
			if(nextSeqNum < 0) {
				nextSeqNum = 0;
			}
			return seqNum;
		}
	}

	/** One of our outgoing packets has been acknowledged. */
	public void ack(int ack, BasePeerNode pn, SessionKey key) {
		long rtt;
		int maxSize;
		boolean validAck = false;
		long ackReceived = System.currentTimeMillis();
		if(logDEBUG) Logger.debug(this, "Acknowledging packet "+ack+" from "+pn);
		SentPacket sent;
		synchronized(sentPackets) {
			sent = sentPackets.remove(ack);
			maxSize = (maxSeenInFlight * 2) + 10;
			if (sent != null && sent.getSentTime() == minSentTimeCached) {
				/*
				 * invalidate cache if removed timestamp was same as cached
				 * minimum timestamp (otherwise, minimum value remains same)
				 */
				minSentTimeCached = Long.MIN_VALUE;
			} else {
				synchronized(NewPacketFormatKeyContext.class) {
					adjusted_l++;
				}
			}
		}
		if(sent != null) {
			rtt = sent.acked(key);
			validAck = true;
		} else {
			if(logDEBUG) Logger.debug(this, "Already acked or lost "+ack);
			long packetSent = lostSentTimes.queryAndRemove(ack);
			if(packetSent < 0) {
				if(logDEBUG) Logger.debug(this, "No time for "+ack+" - maybe acked twice?");
				return;
			}
			rtt = ackReceived - packetSent;
		}

		if(pn == null)
			return;
		int rt = (int) Math.min(rtt, Integer.MAX_VALUE);
		pn.reportPing(rt);
		if(validAck)
			pn.receivedAck(ackReceived);
		PacketThrottle throttle = pn.getThrottle();
		if(throttle == null)
			return;
		throttle.setRoundTripTime(rt);
		if(validAck)
			throttle.notifyOfPacketAcknowledged(maxSize);
	}

	/** Queue an ack.
	 * @return -1 If the ack was already queued, or the total number queued.
	 */
	public int queueAck(int seqno) {
		synchronized(acks) {
			if(!acks.containsKey(seqno)) {
				long now = System.currentTimeMillis();
				acks.put(seqno, now);
				// one ack added, adjust cached value (won't change if was invalid before)
				timeCheckForAcksCached = Math.min(timeCheckForAcksCached, now + MAX_ACK_DELAY);
				if (timeCheckForAcksCached > Long.MIN_VALUE)
					synchronized(NewPacketFormatKeyContext.class) {
						adjusted++;
					}
				return acks.size();
			} else return -1;
		}
	}

	public void sent(int sequenceNumber, int length) {
		synchronized(sentPackets) {
			SentPacket sentPacket = sentPackets.get(sequenceNumber);
			if(sentPacket != null) {
				sentPacket.sent(length);
				/* sent timestamp was changed, invalidate cache */
				minSentTimeCached = Long.MIN_VALUE;
			}
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
				// several acks added, invalidate cache
				timeCheckForAcksCached = Long.MIN_VALUE;
			}
		}
	}
	
	/** Add as many acks as possible to the packet.
	 * @return True if there are any old acks i.e. acks that will force us to send a packet
	 * even if there isn't anything else in it. */
	public AddedAcks addAcks(NPFPacket packet, int maxPacketSize, long now, boolean useCumulativeAcks) {
		boolean mustSend = false;
		HashMap<Integer, Long> moved = null;
		int numAcks = 0;
		packet.setAcknowledgeType(useCumulativeAcks);
		synchronized(acks) {
			Iterator<Map.Entry<Integer, Long>> it = acks.entrySet().iterator();
			while (it.hasNext() && packet.getLength() < maxPacketSize) {
				Map.Entry<Integer, Long> entry = it.next();
				int ack = entry.getKey();
				// All acks must be sent within 200ms.
				if(logDEBUG) Logger.debug(this, "Trying to ack "+ack);
				if(!packet.addAck(ack, maxPacketSize)) {
					if(logDEBUG) Logger.debug(this, "Can't add ack "+ack);
					break;
				}
				if(entry.getValue() + MAX_ACK_DELAY < now)
					mustSend = true;
				if(moved == null) {
					// FIXME some more memory efficient representation, since this will normally be very small?
					moved = new HashMap<Integer, Long>();
				}
				moved.put(ack, entry.getValue());
				++numAcks;
				it.remove();
			}
			if (moved != null) {
				// several acks removed, invalidate cache
				timeCheckForAcksCached = Long.MIN_VALUE;
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
	    sentPacket.sent(length);
		synchronized(sentPackets) {
			sentPackets.put(seqNum, sentPacket);
			/* adjust cache (won't change value if cache was invalid) */
			minSentTimeCached = Math.min(minSentTimeCached, sentPacket.getSentTime());
			if (minSentTimeCached > Long.MIN_VALUE)
				synchronized(NewPacketFormatKeyContext.class) {
					adjusted_l++;
				}
			int inFlight = sentPackets.size();
			if(inFlight > maxSeenInFlight) {
				maxSeenInFlight = inFlight;
				if (logDEBUG) {
					Logger.debug(this, "Max seen in flight new record: " + maxSeenInFlight +
							" for " + this);
				}
			}
		}
	}
	
	private static int calls_l = 0;
	private static int cached_l = 0;
	private static int last_calls_l = 0;
	private static int adjusted_l = 0;
	private static long last_log_time_l = 0;
	private static long uncachedtime_l = 0;

	/* must be called with NewPacketFormatKeyContext.class lock */
	private static void reportCheckForLostPackets(Object o) {
		if ((calls_l & 0xffff) == 0) {
			long now = System.currentTimeMillis();
			if (now - last_log_time_l >= (5*60*1000)) {
				Logger.warning(o, "checkForLostPackets: calls="+calls_l+String.format(" %.2f calls/s",(calls_l-last_calls_l)*1000.0/(now-last_log_time_l))+" cached="+cached_l+String.format(" cached/calls=%.1f",cached_l*100.0/calls_l)+"%"+" adjusted="+adjusted_l+(calls_l-cached_l>0?" uncachedtime="+(uncachedtime_l/(calls_l-cached_l)):""));
				last_calls_l = calls_l;
				last_log_time_l = now;
			}
		}
	}

	public long timeCheckForLostPackets(double averageRTT) {
		long timeCheck = Long.MAX_VALUE;
		// Because MIN_RTT_FOR_RETRANSMIT > MAX_ACK_DELAY, and because averageRTT() includes the
		// actual ack delay, we don't need to add it on here.
		double avgRtt = Math.max(MIN_RTT_FOR_RETRANSMIT, averageRTT);
		long maxDelay = (long)(avgRtt + MAX_ACK_DELAY * 1.1);
		synchronized(sentPackets) {
			synchronized(NewPacketFormatKeyContext.class) {
				reportCheckForLostPackets(this);
				calls_l++;
			}
			if (minSentTimeCached > Long.MIN_VALUE) {
				synchronized(NewPacketFormatKeyContext.class) {
					cached_l++;
				}
				timeCheck = minSentTimeCached;
			} else {
				long t0 = System.nanoTime();
				for (SentPacket s : sentPackets.values()) {
					long t = s.getSentTime();
					if (t < timeCheck) {
						timeCheck = t;
					}
				}
				t0 -= System.nanoTime();
				synchronized(NewPacketFormatKeyContext.class) {
					uncachedtime_l -= t0;
				}
				minSentTimeCached = timeCheck;
			}
		}
		if (timeCheck != Long.MAX_VALUE) {
			timeCheck += maxDelay;
		}
		return timeCheck;
	}

	public void checkForLostPackets(double averageRTT, long curTime, BasePeerNode pn) {
		//Mark packets as lost
		int bigLostCount;
		int count;
		
		// Because MIN_RTT_FOR_RETRANSMIT > MAX_ACK_DELAY, and because averageRTT() includes the
		// actual ack delay, we don't need to add it on here.
		double avgRtt = Math.max(MIN_RTT_FOR_RETRANSMIT, averageRTT);
		long maxDelay = (long)(avgRtt + MAX_ACK_DELAY * 1.1);
		long threshold = curTime - maxDelay;

		final boolean logMINOR = NewPacketFormatKeyContext.logMINOR;
		
		synchronized(sentPackets) {
			synchronized(NewPacketFormatKeyContext.class) {
				reportCheckForLostPackets(this);
				calls_l++;
			}
			if (minSentTimeCached >= threshold) { // && minSentTimeCached != Long.MIN_VALUE [implied by previous condition]
				// when minimum of sentPackets[].getSentTime() >= threshold, "s.getSentTime() < threshold" condition is always false, and no sentPackets needs to be removed
				synchronized(NewPacketFormatKeyContext.class) {
					cached_l++;
				}
				return;
			}
			synchronized(NewPacketFormatKeyContext.class) {
				adjusted_l++;
			}
			long t0 = System.nanoTime();
			bigLostCount = sentPackets.size();
			Iterator<Map.Entry<Integer, SentPacket>> it = sentPackets.entrySet().iterator();
			// recompute cache as we scan over all sentPackets anyway
			minSentTimeCached = Long.MAX_VALUE;
			while(it.hasNext()) {
				Map.Entry<Integer, SentPacket> e = it.next();
				SentPacket s = e.getValue();
				long t = s.getSentTime();
				if (t < threshold) {
					if (logMINOR) {
						Logger.minor(this, "Assuming packet " + e.getKey() + " has been lost. "
						                + "Delay " + (curTime - t) + "ms, "
						                + "threshold " + threshold + "ms");
					}
					// Store the packet sentTime in our lost sent times cache, so we can calculate
					// RTT if an ack may surface later on.
					if(!s.messages.isEmpty()) {
				        lostSentTimes.report(e.getKey(), t);
			        }
			        // Mark the packet as lost and remove it from our active packets.
			        s.lost();
					it.remove();
				} else {
					minSentTimeCached = Math.min(minSentTimeCached, t);
				}
			}
			count = sentPackets.size();
			bigLostCount -= count;
			t0 -= System.nanoTime();
			synchronized(NewPacketFormatKeyContext.class) {
				uncachedtime_l -= t0;
			}
		}
		if(count > 0 && logMINOR)
			Logger.minor(this, "" + count + " packets in flight with threshold " + maxDelay + "ms");
		if(bigLostCount != 0 && pn != null) {
			PacketThrottle throttle = pn.getThrottle();
			if(throttle != null) {
				throttle.notifyOfPacketsLost(bigLostCount);
			}
			pn.backoffOnResend();
		}
	}

	private static int calls = 0;
	private static int cached = 0;
	private static int adjusted = 0;
	private static int last_calls = 0;
	private static long last_log_time = 0;
	private static long uncachedtime = 0;
	public long timeCheckForAcks() {
		long ret = Long.MAX_VALUE;
		synchronized(acks) {
			synchronized(NewPacketFormatKeyContext.class) {
				if ((calls & 0xffff) == 0) {
					long now = System.currentTimeMillis();
					if (now - last_log_time >= (5*60*1000)) {
						Logger.warning(this, "timeCheckForAcks: calls="+calls+String.format(" %.2f calls/s",(calls-last_calls)*1000.0/(now-last_log_time))+" cached="+cached+String.format(" cached/calls=%.1f",cached*100.0/calls)+"%"+" adjusted="+adjusted+(calls-cached>0?" uncachedtime="+(uncachedtime/(calls-cached)):""));
						last_calls = calls;
						last_log_time = now;
					}
				}
				calls++;
			}
			if (timeCheckForAcksCached > Long.MIN_VALUE) {
				synchronized(NewPacketFormatKeyContext.class) {
					cached++; // racing
				}
				return timeCheckForAcksCached;
			}
			long t0 = System.nanoTime();
			for(Long l : acks.values()) {
				long timeout = l + MAX_ACK_DELAY;
				if(ret > timeout) ret = timeout;
			}
			t0 -= System.nanoTime();
			synchronized(NewPacketFormatKeyContext.class) {
				uncachedtime -= t0; // racing
			}
			timeCheckForAcksCached = ret;
		}
		return ret;
	}

	public void disconnected() {
		synchronized(sentPackets) {
			for (SentPacket s: sentPackets.values()) {
				s.lost();
			}
			sentPackets.clear();
			// reset cache to initial state
			minSentTimeCached = Long.MAX_VALUE;
		}
	}
}

