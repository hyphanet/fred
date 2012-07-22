package freenet.node;

import java.util.Arrays;
import java.util.List;

import freenet.crypt.BlockCipher;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.PacketThrottle;
import freenet.pluginmanager.PacketTransportPlugin;
import freenet.pluginmanager.PluginAddress;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
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
	
	protected final FNPPacketMangler packetMangler;
	
	protected PacketFormat packetFormat;
	
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
	

	public PeerPacketTransport(PacketTransportPlugin transportPlugin, FNPPacketMangler packetMangler, PeerNode pn){
		super(transportPlugin, packetMangler, pn);
		this.transportPlugin = transportPlugin;
		this.packetMangler = packetMangler;
	}
	
	public PeerPacketTransport(PacketTransportBundle packetTransportBundle, PeerNode pn){
		this(packetTransportBundle.transportPlugin, packetTransportBundle.packetMangler, pn);
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
		SessionKey kt = cur;
		if(kt != null) {
			long next = kt.packets.getNextUrgentTime();
			t = Math.min(t, next);
			if(next < now && logMINOR)
				Logger.minor(this, "Next urgent time from curTracker less than now");
			if(kt.packets.hasPacketsToResend()) {
				// We could use the original packet send time, but I don't think it matters that much: Old peers with heavy packet loss are probably going to have problems anyway...
				return now;
			}
		}
		kt = prev;
		if(kt != null) {
			long next = kt.packets.getNextUrgentTime();
			t = Math.min(t, next);
			if(next < now && logMINOR)
				Logger.minor(this, "Next urgent time from prevTracker less than now");
			if(kt.packets.hasPacketsToResend()) {
				// We could use the original packet send time, but I don't think it matters that much: Old peers with heavy packet loss are probably going to have problems anyway...
				return now;
			}
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
	
	public long completedHandshake(long thisBootID, BlockCipher outgoingCipher, byte[] outgoingKey, BlockCipher incommingCipher, byte[] incommingKey, PluginAddress replyTo, boolean unverified, int negType, long trackerID, boolean isJFK4, boolean jfk4SameAsOld, byte[] hmacKey, BlockCipher ivCipher, byte[] ivNonce, int ourInitialSeqNum, int theirInitialSeqNum, int ourInitialMsgID, int theirInitialMsgID, long now, boolean newer, boolean older) {
		
		boolean bootIDChanged = false;
		boolean wasARekey = false;
		SessionKey oldPrev = null;
		SessionKey oldCur = null;
		SessionKey prev = null;
		SessionKey newTracker;
		MessageItem[] messagesTellDisconnected = null;
		PacketFormat oldPacketFormat = null;
		PacketTracker packets = null;
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
				boolean notReusingTracker = false;
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
				int firstPacketNumber = (negType >= 5 ? 0 : pn.node.random.nextInt(100 * 1000));
				if(peerConn.currentTracker != null && peerConn.currentTracker.packets.trackerID == trackerID && !peerConn.currentTracker.packets.isDeprecated()) {
					if(isJFK4 && !jfk4SameAsOld)
						Logger.error(this, "In JFK(4), found tracker ID "+trackerID+" but other side says is new! for "+this);
					packets = peerConn.currentTracker.packets;
					if(logMINOR) Logger.minor(this, "Re-using packet tracker ID "+trackerID+" on "+this+" from current "+peerConn.currentTracker);
				} else if(peerConn.previousTracker != null && peerConn.previousTracker.packets.trackerID == trackerID && !peerConn.previousTracker.packets.isDeprecated()) {
					if(isJFK4 && !jfk4SameAsOld)
						Logger.error(this, "In JFK(4), found tracker ID "+trackerID+" but other side says is new! for "+this);
					packets = peerConn.previousTracker.packets;
					if(logMINOR) Logger.minor(this, "Re-using packet tracker ID "+trackerID+" on "+this+" from prev "+peerConn.previousTracker);
				} else if(isJFK4 && jfk4SameAsOld) {
					isTransportConnected = false;
					Logger.error(this, "Can't reuse old tracker ID "+trackerID+" as instructed - disconnecting");
					return -1;
				} else if(trackerID == -1) {
					// Create a new tracker unconditionally
					packets = new PacketTracker(pn, firstPacketNumber);
					if(negType >= 5) {
						if(peerConn.previousTracker != null && peerConn.previousTracker.packets.wasUsed()) {
							oldPrev = peerConn.previousTracker;
							peerConn.previousTracker = null;
							Logger.error(this, "Moving from old packet format to new packet format, previous tracker had packets in progress.");
						}
						if(peerConn.currentTracker != null && peerConn.currentTracker.packets.wasUsed()) {
							oldCur = peerConn.currentTracker;
							peerConn.currentTracker = null;
							Logger.error(this, "Moving from old packet format to new packet format, current tracker had packets in progress.");
						}
					} else {
						notReusingTracker = true;
					}
					if(logMINOR) Logger.minor(this, "Creating new PacketTracker as instructed for "+this);
				} else {
					if(isJFK4 && negType >= 4 && trackerID < 0)
						Logger.error(this, "JFK(4) packet with neg type "+negType+" has negative tracker ID: "+trackerID);
	
					notReusingTracker = true;
					if(isJFK4/* && !jfk4SameAsOld implied */ && trackerID >= 0) {
						packets = new PacketTracker(pn, firstPacketNumber, trackerID);
					} else
						packets = new PacketTracker(pn, firstPacketNumber);
					if(logMINOR) Logger.minor(this, "Creating new tracker (last resort) on "+this);
				}
				if(bootIDChanged || notReusingTracker) {
					if((!bootIDChanged) && notReusingTracker && !(peerConn.currentTracker == null && peerConn.previousTracker == null))
						// FIXME is this a real problem? Clearly the other side has changed trackers for some reason...
						// Normally that shouldn't happen except when a connection times out ... it is probably possible
						// for that to timeout on one side and not the other ...
						Logger.error(this, "Not reusing tracker, so wiping old trackers for "+this);
					oldPrev = peerConn.previousTracker;
					oldCur = peerConn.currentTracker;
					peerConn.previousTracker = null;
					peerConn.currentTracker = null;
				}
				if(bootIDChanged) {
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
				newTracker = new SessionKey(pn, packets, outgoingCipher, outgoingKey, incommingCipher, incommingKey, ivCipher, ivNonce, hmacKey, new NewPacketFormatKeyContext(ourInitialSeqNum, theirInitialSeqNum));
				if(logMINOR) Logger.minor(this, "New key tracker in completedHandshake: "+newTracker+" for "+packets+" for "+pn.shortToString()+" neg type "+negType);
				if(unverified) {
					if(peerConn.unverifiedTracker != null) {
						// Keep the old unverified tracker if possible.
						if(peerConn.previousTracker == null)
							peerConn.previousTracker = peerConn.unverifiedTracker;
					}
					peerConn.unverifiedTracker = newTracker;
					if(peerConn.currentTracker == null || peerConn.currentTracker.packets.isDeprecated())
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
					peerConn.maybeSwapTrackers();
					prev = peerConn.previousTracker;
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
					if(negType < 5) {
						packetFormat = new FNPWrapper(pn);
					} else {
						packetFormat = new NewPacketFormat(pn, ourInitialMsgID, theirInitialMsgID);
					}
				}
				// Completed setup counts as received data packet, for purposes of avoiding spurious disconnections.
				timeLastReceivedTransportPacket = now;
				timeLastReceivedTransportDataPacket = now;
				timeLastReceivedTransportAck = now;
			}
		}
		if(messagesTellDisconnected != null) {
			for(int i=0;i<messagesTellDisconnected.length;i++) {
				messagesTellDisconnected[i].onDisconnect();
			}
		}

		if(bootIDChanged) {
			pn.node.lm.lostOrRestartedNode(pn);
			pn.node.usm.onRestart(pn);
			pn.node.onRestartOrDisconnect(pn);
		}
		if(oldPrev != null && oldPrev.packets != newTracker.packets)
			oldPrev.packets.completelyDeprecated(newTracker);
		if(oldPrev != null) oldPrev.disconnected(true);
		if(oldCur != null && oldCur.packets != newTracker.packets)
			oldCur.packets.completelyDeprecated(newTracker);
		if(oldCur != null) oldCur.disconnected(true);
		if(prev != null && prev.packets != newTracker.packets)
			prev.packets.deprecated();
		if(oldPacketFormat != null) {
			List<MessageItem> tellDisconnect = oldPacketFormat.onDisconnect();
			if(tellDisconnect != null)
				for(MessageItem item : tellDisconnect) {
					item.onDisconnect();
				}
		}
		PacketThrottle throttle;
		synchronized(pn) {
			throttle = pn.getThrottle();
		}
		if(throttle != null) throttle.maybeDisconnected();
		Logger.normal(this, "Completed handshake with " + this + " on " + replyTo + " - current: " + peerConn.currentTracker +
			" old: " + peerConn.previousTracker + " unverified: " + peerConn.unverifiedTracker + " bootID: " + thisBootID + (bootIDChanged ? "(changed) " : "") + " for " + pn.shortToString());

		pn.setPeerNodeStatus(now);

		if(newer || older || !isTransportConnected())
			pn.node.peers.disconnected(pn);
		else if(!wasARekey) {
			pn.node.peers.addConnectedPeer(pn);
			pn.maybeOnConnect();
		}
		
		pn.crypto.maybeBootConnection(pn, replyTo, transportPlugin);

		return packets.trackerID;
	}
	
	public boolean isTransportConnected() {
		long now = System.currentTimeMillis(); // no System.currentTimeMillis in synchronized
		synchronized(this) {
			if(isTransportConnected && getCurrentKeyTracker() != null && !getCurrentKeyTracker().packets.isDeprecated()) {
				timeLastConnectedTransport = now;
				return true;
			}
			return false;
		}
	}
	
}
