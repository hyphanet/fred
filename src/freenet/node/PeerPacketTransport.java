package freenet.node;

import java.net.InetAddress;
import java.util.Arrays;

import freenet.crypt.BlockCipher;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.io.xfer.PacketThrottle;
import freenet.pluginmanager.MalformedPluginAddressException;
import freenet.pluginmanager.PacketTransportPlugin;
import freenet.pluginmanager.PluginAddress;
import freenet.pluginmanager.UnsupportedIPAddressOperationException;
import freenet.support.Fields;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.transport.ip.IPUtil;
/**
 * This class will be used to store keys, timing fields, etc. by PeerNode for each transport for handshaking. 
 * Once handshake is completed a PeerPacketConnection object is used to store the session keys.<br><br>
 * 
 * <b>Convention:</b> The "Transport" word is used in fields that are transport specific, and are also present in PeerNode.
 * These fields will allow each Transport to behave differently. The existing fields in PeerNode will be used for 
 * common functionality.
 * The fields without "Transport" in them are those which in the long run must be removed from PeerNode.
 * <br> e.g.: <b>isTransportRekeying</b> is used if the individual transport is rekeying;
 * <b>isRekeying</b> will be used in common to all transports in PeerNode.
 * <br> e.g.: <b>jfkKa</b>, <b>incommingKey</b>, etc. should be transport specific and must be moved out of PeerNode 
 * once existing UDP is fully converted to the new TransportPlugin format.
 * @author chetan
 *
 */
public class PeerPacketTransport extends PeerTransport {
	
	protected final PacketTransportPlugin transportPlugin;
	
	protected final OutgoingPacketMangler outgoingMangler;
	
	protected PacketFormat packetFormat;
	
	protected final PacketThrottle packetThrottle = null;
	
	/*
	 * Time related fields
	 */
	/** When did we last send a packet? */
	protected long timeLastSentTransportPacket;
	/** When did we last receive a packet? */
	protected long timeLastReceivedTransportPacket;
	/** When did we last receive a non-auth packet? */
	protected long timeLastReceivedTransportDataPacket;
	
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
	

	public PeerPacketTransport(PacketTransportPlugin transportPlugin, OutgoingPacketMangler outgoingMangler, PeerNode pn){
		super(transportPlugin, outgoingMangler, pn);
		this.transportPlugin = transportPlugin;
		this.outgoingMangler = outgoingMangler;
	}
	
	public PeerPacketTransport(PacketTransportBundle packetTransportBundle, PeerNode pn){
		this(packetTransportBundle.transportPlugin, packetTransportBundle.packetMangler, pn);
	}
	
	/*
	 * 
	 * Time related methods
	 * 
	 */
	
	public void sentPacket() {
		timeLastSentTransportPacket = System.currentTimeMillis();
	}
	
	public long lastSentTransportPacketTime() {
		return timeLastSentTransportPacket;
	}
	
	public synchronized long lastReceivedTransportPacketTime() {
		return timeLastReceivedTransportPacket;
	}

	public synchronized long lastReceivedTransportDataPacketTime() {
		return timeLastReceivedTransportDataPacket;
	}
	
	/**
	* Update timeLastReceivedPacket
	* @throws NotConnectedException
	* @param dontLog If true, don't log an error or throw an exception if we are not connected. This
	* can be used in handshaking when the connection hasn't been verified yet.
	* @param dataPacket If this is a real packet, as opposed to a handshake packet.
	*/
	public void receivedPacket(boolean dontLog, boolean dataPacket) {
		synchronized(this) {
			if((!isTransportConnected) && (!dontLog)) {
				// Don't log if we are disconnecting, because receiving packets during disconnecting is normal.
				// That includes receiving packets after we have technically disconnected already.
				// A race condition involving forceCancelDisconnecting causing a mistaken log message anyway
				// is conceivable, but unlikely...
				if((peerConn.unverifiedTracker == null) && (peerConn.currentTracker == null) && !pn.isDisconnecting())
					Logger.error(this, "Received packet while disconnected!: " + this, new Exception("error"));
				else
					if(logMINOR)
						Logger.minor(this, "Received packet while disconnected on " + this + " - recently disconnected() ?");
			} else {
				if(logMINOR) Logger.minor(this, "Received packet on "+this);
			}
		}
		long now = System.currentTimeMillis();
		synchronized(this) {
			timeLastReceivedTransportPacket = now;
			if(dataPacket)
				timeLastReceivedTransportDataPacket = now;
		}
	}
	
	/**
	* @return The time at which we must send a packet, even if
	* it means it will only contains ack requests etc., or
	* Long.MAX_VALUE if we have no pending ack request/acks/etc.
	* Note that if this is less than now, it may not be entirely
	* accurate i.e. we definitely must send a packet, but don't
	* rely on it to tell you exactly how overdue we are.
	*/
	public long getNextUrgentTime(long now) {
		long t = Long.MAX_VALUE;
		SessionKey cur;
		SessionKey prev;
		PacketFormat pf;
		synchronized(this) {
			if(!isTransportConnected) return Long.MAX_VALUE;
			cur = peerConn.currentTracker;
			prev = peerConn.previousTracker;
			pf = packetFormat;
			if(cur == null && prev == null) return Long.MAX_VALUE;
		}
		if(pf != null) {
			boolean canSend = cur != null && pf.canSend(cur);
			if(canSend) { // New messages are only sent on cur.
				long l = pn.getMessageQueue().getNextUrgentTime(t, 0); // Need an accurate value even if in the past.
				if(t >= now && l < now && logMINOR)
					Logger.minor(this, "Next urgent time from message queue less than now");
				else if(logDEBUG)
					Logger.debug(this, "Next urgent time is "+(l-now)+"ms on "+this);
				t = l;
			}
			long l = pf.timeNextUrgent(canSend);
			if(l < now && logMINOR)
				Logger.minor(this, "Next urgent time from packet format less than now on "+this);
			t = Math.min(t, l);
		}
		return t;
	}
	
	public long completedHandshake(final long thisBootID, BlockCipher outgoingCipher, byte[] outgoingKey, BlockCipher incommingCipher, byte[] incommingKey, final PluginAddress replyTo, boolean unverified, int negType, long trackerID, boolean isJFK4, boolean jfk4SameAsOld, byte[] hmacKey, BlockCipher ivCipher, byte[] ivNonce, int ourInitialSeqNum, int theirInitialSeqNum, final long now, final boolean newer, final boolean older) {
		
		boolean bootIDChanged = false;
		boolean wasARekey = false;
		SessionKey oldPrev = null;
		SessionKey oldCur = null;;
		SessionKey newTracker;
		MessageItem[] messagesTellDisconnected = null;
		PacketFormat oldPacketFormat = null;
		synchronized(this) {
			pn.setDisconnecting(false);
			// FIXME this shouldn't happen, does it?
			synchronized(peerConn) {
				if(peerConn.currentTracker != null) {
					if(Arrays.equals(outgoingKey, peerConn.currentTracker.outgoingKey)
							&& Arrays.equals(incommingKey, peerConn.currentTracker.incommingKey)) {
						Logger.error(this, "completedHandshake() with identical key to current, maybe replayed JFK(4)?");
						return -1;
					}
				}
				if(peerConn.previousTracker != null) {
					if(Arrays.equals(outgoingKey, peerConn.previousTracker.outgoingKey)
							&& Arrays.equals(incommingKey, peerConn.previousTracker.incommingKey)) {
						Logger.error(this, "completedHandshake() with identical key to previous, maybe replayed JFK(4)?");
						return -1;
					}
				}
				if(peerConn.unverifiedTracker != null) {
					if(Arrays.equals(outgoingKey, peerConn.unverifiedTracker.outgoingKey)
							&& Arrays.equals(incommingKey, peerConn.unverifiedTracker.incommingKey)) {
						Logger.error(this, "completedHandshake() with identical key to unverified, maybe replayed JFK(4)?");
						return -1;
					}
				}
				transportHandshakeCount = 0;
				// Don't reset the uptime if we rekey
				if(!isTransportConnected) {
					transportConnectedTime = now;
					pn.resetCountSelectionsSinceConnected();
					sentInitialMessagesTransport = false;
				} else
					wasARekey = true;
				isTransportConnected = true;
				bootIDChanged = (thisBootID != pn.getBootID());
				if(pn.myLastSuccessfulBootID != pn.getOutgoingBootID()) {
					// If our own boot ID changed, because we forcibly disconnected, 
					// we need to use a new tracker. This is equivalent to us having restarted,
					// from the point of view of the other side, but since we haven't we need
					// to track it here.
					bootIDChanged = true;
					pn.myLastSuccessfulBootID = pn.getOutgoingBootID();
				}
				if(bootIDChanged && wasARekey) {
					// This can happen if the other side thought we disconnected but we didn't think they did.
					Logger.normal(this, "Changed boot ID while rekeying! from " + pn.getBootID() + " to " + thisBootID + " for " + detectedTransportAddress);
					wasARekey = false;
					transportConnectedTime = now;
					pn.resetCountSelectionsSinceConnected();
					sentInitialMessagesTransport = false;
				} else if(bootIDChanged && logMINOR)
					Logger.minor(this, "Changed boot ID from " + pn.getBootID() + " to " + thisBootID + " for " + detectedTransportAddress);
				pn.setBootID(thisBootID);
				if(bootIDChanged) {
						// FIXME is this a real problem? Clearly the other side has changed trackers for some reason...
						// Normally that shouldn't happen except when a connection times out ... it is probably possible
						// for that to timeout on one side and not the other ...
					oldPrev = peerConn.previousTracker;
					oldCur = peerConn.currentTracker;
					peerConn.previousTracker = null;
					peerConn.currentTracker = null;
					// Messages do not persist across restarts.
					// Generally they would be incomprehensible, anything that isn't should be sent as
					// connection initial messages by maybeOnConnect().
					messagesTellDisconnected = pn.grabQueuedMessageItems();
					pn.setMainJarOfferedVersion(0);
					oldPacketFormat = packetFormat;
					packetFormat = null;
				} else {
					// else it's a rekey
				}
				newTracker = new SessionKey(pn, outgoingCipher, outgoingKey, incommingCipher, incommingKey, ivCipher, ivNonce, hmacKey, new NewPacketFormatKeyContext(ourInitialSeqNum, theirInitialSeqNum), trackerID, transportPlugin);
				if(logMINOR) Logger.minor(this, "New key tracker in completedHandshake: "+newTracker+" for "+pn.shortToString()+" neg type "+negType);
				if(unverified) {
					if(peerConn.unverifiedTracker != null) {
						// Keep the old unverified tracker if possible.
						if(peerConn.previousTracker == null)
							peerConn.previousTracker = peerConn.unverifiedTracker;
					}
					peerConn.unverifiedTracker = newTracker;
					if(peerConn.currentTracker == null)
						isTransportConnected = false;
				} else {
					oldPrev = peerConn.previousTracker;
					peerConn.previousTracker = peerConn.currentTracker;
					peerConn.currentTracker = newTracker;
					// Keep the old unverified tracker.
					// In case of a race condition (two setups between A and B complete at the same time),
					// we might want to keep the unverified tracker rather than the previous tracker.
					pn.neverConnected = false;
					pn.maybeClearPeerAddedTimeOnConnect();
				}
				ctxTransport = null;
				isTransportRekeying = false;
				timeTransportLastRekeyed = now - (unverified ? 0 : FNPPacketMangler.MAX_SESSION_KEY_REKEYING_DELAY / 2);
				peerConn.totalBytesExchangedWithCurrentTracker = 0;
				// This has happened in the past, and caused problems, check for it.
				if(peerConn.currentTracker != null && peerConn.previousTracker != null &&
						Arrays.equals(peerConn.currentTracker.outgoingKey, peerConn.previousTracker.outgoingKey) &&
						Arrays.equals(peerConn.currentTracker.incommingKey, peerConn.previousTracker.incommingKey))
					Logger.error(this, "peerConn.currentTracker key equals previousTracker key: cur "+peerConn.currentTracker+" prev "+peerConn.previousTracker);
				if(peerConn.previousTracker != null && peerConn.unverifiedTracker != null &&
						Arrays.equals(peerConn.previousTracker.outgoingKey, peerConn.unverifiedTracker.outgoingKey) &&
						Arrays.equals(peerConn.previousTracker.incommingKey, peerConn.unverifiedTracker.incommingKey))
					Logger.error(this, "previousTracker key equals unverifiedTracker key: prev "+peerConn.previousTracker+" unv "+peerConn.unverifiedTracker);
				timeLastSentTransportPacket = now;
				if(packetFormat == null) {
					packetFormat = new NewPacketFormat(pn, this);
				}
				// Completed setup counts as received data packet, for purposes of avoiding spurious disconnections.
				timeLastReceivedTransportPacket = now;
				timeLastReceivedTransportDataPacket = now;
				timeLastReceivedTransportAck = now;
			}
		}
		
		// The following is a separate job to avoid deadlocks
		
		final boolean bootIDChangedInst = bootIDChanged;
		final SessionKey oldPrevInst = oldPrev;
		final SessionKey oldCurInst = oldCur;
		final boolean wasARekeyInst = wasARekey;
		final MessageItem[] messagesTellDisconnectedInst = messagesTellDisconnected;
		final PacketFormat oldPacketFormatInst = oldPacketFormat;
		
		pn.node.executor.execute(new Runnable() {
			@Override
			public void run() {
				if(messagesTellDisconnectedInst != null) {
					for(int i=0;i<messagesTellDisconnectedInst.length;i++) {
						messagesTellDisconnectedInst[i].onDisconnect();
					}
				}

				if(bootIDChangedInst) {
					pn.node.lm.lostOrRestartedNode(pn);
					pn.node.usm.onRestart(pn);
					pn.node.onRestartOrDisconnect(pn);
					// We need to disconnect all the other transports since the bootID has changed.
					// We keep only this transport and reconnect the others
					pn.disconnectAllExceptOne(PeerPacketTransport.this);
				}
				if(oldPrevInst != null) oldPrevInst.disconnected();
				if(oldCurInst != null) oldCurInst.disconnected();
				if(oldPacketFormatInst != null) {
					oldPacketFormatInst.onDisconnect();
				}
				PacketThrottle throttle;
				synchronized(pn) {
					throttle = pn.getThrottle();
				}
				if(throttle != null) throttle.maybeDisconnected();
				Logger.normal(this, "Completed handshake with " + this + " on " + replyTo + " - current: " + peerConn.currentTracker +
					" old: " + peerConn.previousTracker + " unverified: " + peerConn.unverifiedTracker + " bootID: " + thisBootID + (bootIDChangedInst ? "(changed) " : "") + " for " + pn.shortToString());

				pn.setPeerNodeStatus(now);

				if(newer || older || !isTransportConnected())
					pn.node.peers.disconnected(pn);
				else if(!wasARekeyInst) {
					pn.node.peers.addConnectedPeer(pn);
					pn.maybeOnConnect();
				}
				
				pn.crypto.maybeBootConnection(pn, replyTo, transportPlugin);
			}
		});
		

		return trackerID;
	}
	
	/**
	* Called when a packet is successfully decrypted on a given
	* SessionKey for this node. Will promote the unverifiedTracker
	* if necessary.
	*/
	@Override
	public void verified(SessionKey tracker) {
		long now = System.currentTimeMillis();
		SessionKey completelyDeprecatedTracker;
		synchronized(peerConn) {
			if(tracker == peerConn.unverifiedTracker) {
				if(logMINOR)
					Logger.minor(this, "Promoting unverified tracker " + tracker + " for " + detectedTransportAddress);
				completelyDeprecatedTracker = peerConn.previousTracker;
				peerConn.previousTracker = peerConn.currentTracker;
				peerConn.currentTracker = peerConn.unverifiedTracker;
				peerConn.unverifiedTracker = null;
				isTransportConnected = true;
				pn.neverConnected = false;
				pn.maybeClearPeerAddedTimeOnConnect();
				ctxTransport = null;
			} else
				return;
		}
		pn.maybeSendInitialMessages(transportPlugin);
		pn.setPeerNodeStatus(now);
		pn.node.peers.addConnectedPeer(pn);
		pn.maybeOnConnect();
		if(completelyDeprecatedTracker != null) {
			completelyDeprecatedTracker.disconnected();
		}
	}
	
	@Override
	public void maybeRekey() {
		long now = System.currentTimeMillis();
		boolean shouldTransportDisconnect = false;
		boolean shouldReturn = false;
		boolean shouldRekey = false;
		long timeWhenRekeyingShouldOccur = 0;

		synchronized (this) {
			timeWhenRekeyingShouldOccur = timeTransportLastRekeyed + FNPPacketMangler.SESSION_KEY_REKEYING_INTERVAL;
			shouldTransportDisconnect = (timeWhenRekeyingShouldOccur + FNPPacketMangler.MAX_SESSION_KEY_REKEYING_DELAY < now) && isTransportRekeying;
			shouldReturn = isTransportRekeying || !isTransportConnected;
			shouldRekey = (timeWhenRekeyingShouldOccur < now);
			if((!shouldRekey) && peerConn.totalBytesExchangedWithCurrentTracker > FNPPacketMangler.AMOUNT_OF_BYTES_ALLOWED_BEFORE_WE_REKEY) {
				shouldRekey = true;
				timeWhenRekeyingShouldOccur = now;
			}
		}

		if(shouldTransportDisconnect) {
			String time = TimeUtil.formatTime(FNPPacketMangler.MAX_SESSION_KEY_REKEYING_DELAY);
			System.err.println("The peer (" + this + ") has been asked to rekey " + time + " ago... force disconnect.");
			Logger.error(this, "The peer (" + this + ") has been asked to rekey " + time + " ago... force disconnect.");
			pn.disconnectTransport(true, this);
		} else if (shouldReturn || hasLiveHandshake(now)) {
			return;
		} else if(shouldRekey) {
			startRekeying();
		}
	}
	
	/**
	* Send a payload-less packet on either key if necessary.
	* @throws PacketSequenceException If there is an error sending the packet
	* caused by a sequence inconsistency.
	*/
	public boolean sendAnyUrgentNotifications(boolean forceSendPrimary) {
		return false;
	}
	
	public Message createSentPacketsMessage() {
		long[][] sent = getSentPacketTimesHashes();
		long[] times = sent[0];
		long[] hashes = sent[1];
		long now = System.currentTimeMillis();
		long horizon = now - Integer.MAX_VALUE;
		int skip = 0;
		for(int i = 0; i < times.length; i++) {
			long time = times[i];
			if(time < horizon)
				skip++;
			else
				break;
		}
		int[] timeDeltas = new int[times.length - skip];
		for(int i = skip; i < times.length; i++)
			timeDeltas[i] = (int) (now - times[i]);
		if(skip != 0) {
			// Unlikely code path, only happens with very long uptime.
			// Trim hashes too.
			long[] newHashes = new long[hashes.length - skip];
			System.arraycopy(hashes, skip, newHashes, 0, hashes.length - skip);
		}
		return DMT.createFNPSentPacketsTransport(transportName, timeDeltas, hashes, now);
	}
	
	// Recent packets sent/received
	// We record times and weak short hashes of the last 64 packets
	// sent/received. When we connect successfully, we send the data
	// on what packets we have sent, and the recipient can compare
	// this to their records of received packets to determine if there
	// is a problem, which usually indicates not being port forwarded.
	
	static final short TRACK_PACKETS = 64;
	private final long[] packetsSentTimes = new long[TRACK_PACKETS];
	private final long[] packetsRecvTimes = new long[TRACK_PACKETS];
	private final long[] packetsSentHashes = new long[TRACK_PACKETS];
	private final long[] packetsRecvHashes = new long[TRACK_PACKETS];
	private short sentPtr;
	private short recvPtr;
	private boolean sentTrackPackets;
	private boolean recvTrackPackets;

	public void reportIncomingPacket(byte[] buf, int offset, int length, long now) {
		pn.reportIncomingBytes(length);
		long hash = Fields.longHashCode(buf, offset, length);
		synchronized(this) {
			packetsRecvTimes[recvPtr] = now;
			packetsRecvHashes[recvPtr] = hash;
			recvPtr++;
			if(recvPtr == TRACK_PACKETS) {
				recvPtr = 0;
				recvTrackPackets = true;
			}
		}
	}

	public void reportOutgoingPacket(byte[] buf, int offset, int length, long now) {
		pn.reportOutgoingBytes(length);
		long hash = Fields.longHashCode(buf, offset, length);
		synchronized(this) {
			packetsSentTimes[sentPtr] = now;
			packetsSentHashes[sentPtr] = hash;
			sentPtr++;
			if(sentPtr == TRACK_PACKETS) {
				sentPtr = 0;
				sentTrackPackets = true;
			}
		}
	}

	/**
	 * @return a long[] consisting of two arrays, the first being packet times,
	 * the second being packet hashes.
	 */
	public synchronized long[][] getSentPacketTimesHashes() {
		short count = sentTrackPackets ? TRACK_PACKETS : sentPtr;
		long[] times = new long[count];
		long[] hashes = new long[count];
		if(!sentTrackPackets) {
			System.arraycopy(packetsSentTimes, 0, times, 0, sentPtr);
			System.arraycopy(packetsSentHashes, 0, hashes, 0, sentPtr);
		} else {
			System.arraycopy(packetsSentTimes, sentPtr, times, 0, TRACK_PACKETS - sentPtr);
			System.arraycopy(packetsSentTimes, 0, times, TRACK_PACKETS - sentPtr, sentPtr);
			System.arraycopy(packetsSentHashes, sentPtr, hashes, 0, TRACK_PACKETS - sentPtr);
			System.arraycopy(packetsSentHashes, 0, hashes, TRACK_PACKETS - sentPtr, sentPtr);
		}
		return new long[][]{times, hashes};
	}

	/**
	 * @return a long[] consisting of two arrays, the first being packet times,
	 * the second being packet hashes.
	 */
	public synchronized long[][] getRecvPacketTimesHashes() {
		short count = recvTrackPackets ? TRACK_PACKETS : recvPtr;
		long[] times = new long[count];
		long[] hashes = new long[count];
		if(!recvTrackPackets) {
			System.arraycopy(packetsRecvTimes, 0, times, 0, recvPtr);
			System.arraycopy(packetsRecvHashes, 0, hashes, 0, recvPtr);
		} else {
			System.arraycopy(packetsRecvTimes, recvPtr, times, 0, TRACK_PACKETS - recvPtr);
			System.arraycopy(packetsRecvTimes, 0, times, TRACK_PACKETS - recvPtr, recvPtr);
			System.arraycopy(packetsRecvHashes, recvPtr, hashes, 0, TRACK_PACKETS - recvPtr);
			System.arraycopy(packetsRecvHashes, 0, hashes, TRACK_PACKETS - recvPtr, recvPtr);
		}
		return new long[][]{times, hashes};
	}
	
	/**
	 * @return The ID of a reusable PacketTracker if there is one, otherwise -1.
	 */
	public long getReusableTrackerID() {
		SessionKey cur;
		synchronized(peerConn) {
			cur = peerConn.currentTracker;
		}
		if(cur == null) {
			if(logMINOR) Logger.minor(this, "getReusableTrackerID(): cur = null on "+this);
			return -1;
		}
		if(logMINOR) Logger.minor(this, "getReusableTrackerID(): "+cur.trackerID+" on "+this);
		return cur.trackerID;
	}
	
	public void handleSentPackets(Message m) {

		// IMHO it's impossible to make this work reliably on lossy connections, especially highly saturated upstreams.
		// If it was possible it would likely involve a lot of work, refactoring, voting between peers, marginal results,
		// very slow accumulation of data etc.

//		long now = System.currentTimeMillis();
//		synchronized(this) {
//			if(forceDisconnectCalled)
//				return;
//			/*
//			 * I've had some very strange results from seed clients!
//			 * One showed deltas of over 10 minutes... how is that possible? The PN wouldn't reconnect?!
//			 */
//			if(!isRealConnection())
//				return; // The packets wouldn't have been assigned to this PeerNode!
////			if(now - this.timeLastConnected < SENT_PACKETS_MAX_TIME_AFTER_CONNECT)
////				return;
//		}
//		long baseTime = m.getLong(DMT.TIME);
//		baseTime += this.clockDelta;
//		// Should be a reasonable approximation now
//		int[] timeDeltas = Fields.bytesToInts(((ShortBuffer) m.getObject(DMT.TIME_DELTAS)).getData());
//		long[] packetHashes = Fields.bytesToLongs(((ShortBuffer) m.getObject(DMT.HASHES)).getData());
//		long[] times = new long[timeDeltas.length];
//		for(int i = 0; i < times.length; i++)
//			times[i] = baseTime - timeDeltas[i];
//		long tolerance = 60 * 1000 + (Math.abs(timeDeltas[0]) / 20); // 1 minute or 5% of full interval
//		synchronized(this) {
//			// They are in increasing order
//			// Loop backwards
//			long otime = Long.MAX_VALUE;
//			long[][] sent = getRecvPacketTimesHashes();
//			long[] sentTimes = sent[0];
//			long[] sentHashes = sent[1];
//			short sentPtr = (short) (sent.length - 1);
//			short notFoundCount = 0;
//			short consecutiveNotFound = 0;
//			short longestConsecutiveNotFound = 0;
//			short ignoredUptimeCount = 0;
//			short found = 0;
//			//The arrays are constructed from received data, don't throw an ArrayIndexOutOfBoundsException if they are different sizes.
//			int shortestArray=times.length;
//			if (shortestArray > packetHashes.length)
//				shortestArray = packetHashes.length;
//			for(short i = (short) (shortestArray-1); i >= 0; i--) {
//				long time = times[i];
//				if(time > otime) {
//					Logger.error(this, "Inconsistent time order: [" + i + "]=" + time + " but [" + (i + 1) + "] is " + otime);
//					return;
//				} else
//					otime = time;
//				long hash = packetHashes[i];
//				// Search for the hash.
//				short match = -1;
//				// First try forwards
//				for(short j = sentPtr; j < sentTimes.length; j++) {
//					long ttime = sentTimes[j];
//					if(sentHashes[j] == hash) {
//						match = j;
//						sentPtr = j;
//						break;
//					}
//					if(ttime - time > tolerance)
//						break;
//				}
//				if(match == -1)
//					for(short j = (short) (sentPtr - 1); j >= 0; j--) {
//						long ttime = sentTimes[j];
//						if(sentHashes[j] == hash) {
//							match = j;
//							sentPtr = j;
//							break;
//						}
//						if(time - ttime > tolerance)
//							break;
//					}
//				if(match == -1) {
//					long mustHaveBeenUpAt = now - (int)(timeDeltas[i] * 1.1) - 100;
//					if(this.crypto.socket.getStartTime() > mustHaveBeenUpAt) {
//						ignoredUptimeCount++;
//					} else {
//						// Not found
//						consecutiveNotFound++;
//						notFoundCount++;
//					}
//				} else {
//					if(consecutiveNotFound > longestConsecutiveNotFound)
//						longestConsecutiveNotFound = consecutiveNotFound;
//					consecutiveNotFound = 0;
//					found++;
//				}
//			}
//			if(consecutiveNotFound > longestConsecutiveNotFound)
//				longestConsecutiveNotFound = consecutiveNotFound;
//			Logger.error(this, "Packets: "+packetHashes.length+" not found "+notFoundCount+" consecutive not found "+consecutiveNotFound+" longest consecutive not found "+longestConsecutiveNotFound+" ignored due to uptime: "+ignoredUptimeCount+" found: "+found);
//			if(longestConsecutiveNotFound > TRACK_PACKETS / 2) {
//				manyPacketsClaimedSentNotReceived = true;
//				timeManyPacketsClaimedSentNotReceived = now;
//				Logger.error(this, "" + consecutiveNotFound + " consecutive packets not found on " + userToString());
//				SocketHandler handler = outgoingMangler.getSocketHandler();
//				if(handler instanceof PortForwardSensitiveSocketHandler) {
//					((PortForwardSensitiveSocketHandler) handler).rescanPortForward();
//				}
//			}
//		}
//		if(manyPacketsClaimedSentNotReceived) {
//			outgoingMangler.setPortForwardingBroken();
//		}
	}

	// FIXME incomplete. Finish NPF completely
	/**
	 * Called only through PeerNode or PacketSender.
	 */
	@Override
	public boolean disconnectTransport(boolean dumpTrackers) {
		final long now = System.currentTimeMillis();
		boolean ret;
		SessionKey cur, prev, unv;
		synchronized(peerConn) {
			ret = isTransportConnected;
			// Force re negotiation.
			isTransportConnected = false;
			// Prevent sending packets to the node until that happens.
			cur = peerConn.currentTracker;
			prev = peerConn.previousTracker;
			unv = peerConn.unverifiedTracker;
			if(dumpTrackers) {
				peerConn.currentTracker = null;
				peerConn.previousTracker = null;
				peerConn.unverifiedTracker = null;
			}
			sendTransportHandshakeTime = now;
			timeTransportPrevDisconnect = timeTransportLastDisconnect;
			timeTransportLastDisconnect = now;
			
			packetFormat.onDisconnect();
			packetFormat = null;
		}
		if(cur != null) cur.disconnected();
		if(prev != null) prev.disconnected();
		if(unv != null) unv.disconnected();
		return ret;
	}
	
	public void checkForLostPackets() {
		PacketFormat pf;
		synchronized(this) {
			pf = packetFormat;
			if(pf == null) return;
		}
		pf.checkForLostPackets();
	}
	
	public long timeSendAcks() {
		PacketFormat pf;
		synchronized(this) {
			pf = packetFormat;
			if(pf == null) return Long.MAX_VALUE;
		}
		return pf.timeSendAcks();
	}
	
	public synchronized boolean noContactDetails() {
		return handshakeTransportAddresses == null || handshakeTransportAddresses.length == 0;
	}
	
	public boolean shouldThrottle() {
		if(pn.node.throttleLocalData) return true;
		PluginAddress address = null;
		synchronized(this) {
			address = detectedTransportAddress;
		}
		if(address == null) return true; // presumably
		InetAddress addr;
		try {
			addr = address.getFreenetAddress().getAddress();
			if(addr == null) return true; // presumably
			return IPUtil.isValidAddress(addr, false);
		}catch(UnsupportedIPAddressOperationException e) {
			return false; //Non ip based plugin address. Since it is not null we can safely assume it is fine.
		}
		
	}
	
	/**
	 * Maybe send something. A SINGLE PACKET.
	 * Don't send everything at once, for two reasons:
	 * 1. It is possible for a node to have a very long backlog.
	 * 2. Sometimes sending a packet can take a long time.
	 * 3. In the near future PacketSender will be responsible for output bandwidth
	 * throttling.
	 * So it makes sense to send a single packet and round-robin.
	 * @param now
	 * @param rpiTemp
	 * @param rpiTemp
	 * @throws BlockedTooLongException
	 */
	public boolean maybeSendPacket(long now, boolean ackOnly) throws BlockedTooLongException {
		PacketFormat pf;
		synchronized(this) {
			if(packetFormat == null) return false;
			pf = packetFormat;
		}
		return pf.maybeSendPacket(now, ackOnly);
	}
	
	public long timeCheckForLostPackets() {
		PacketFormat pf;
		synchronized(this) {
			pf = packetFormat;
			if(pf == null) return Long.MAX_VALUE;
		}
		return pf.timeCheckForLostPackets();
	}
	
	public boolean fullPacketQueued() {
		PacketFormat pf;
		synchronized(this) {
			pf = packetFormat;
			if(pf == null) return false;
		}
		return pf.fullPacketQueued(pn.getMaxPacketSize());
	}
	
	public void sendEncryptedPacket(byte[] data) throws LocalAddressException {
		try {
			transportPlugin.sendPacket(data, getAddress(), allowLocalAddresses());
		} catch (MalformedPluginAddressException e) {
			Logger.error(this, "It is highly unlikely that this Address does not belong to " 
					+ transportName + " plugin. Something went wrong with getAddress()", e);
		}
	}
	
}
