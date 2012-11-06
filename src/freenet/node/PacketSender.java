/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import freenet.clients.http.ExternalLinkToadlet;
import freenet.l10n.NodeL10n;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.PluginAddress;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.OOMHandler;
import freenet.support.TimeUtil;
import freenet.support.io.NativeThread;
import freenet.support.math.MersenneTwister;

/**
 * @author amphibian
 *
 *         Thread that sends a packet whenever: - A packet needs to be resent immediately -
 *         Acknowledgments or resend requests need to be sent urgently.
 */
// j16sdiz (22-Dec-2008):
// FIXME this is the only class implements Ticker, everbody is using this as
// a generic task scheduler. Either rename this class, or create another tricker for non-Packet tasks
public class PacketSender implements Runnable {

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

	/** Maximum time we will queue a message for in milliseconds */
	static final int MAX_COALESCING_DELAY = 100;
	/** Maximum time we will queue a message for in milliseconds if it is bulk data.
	 * Note that we will send the data immediately anyway because it will normally be big
	 * enough to send a full packet. However this impacts the choice of whether to send
	 * realtime or bulk data, see PeerMessageQueue.addMessages(). */
	static final int MAX_COALESCING_DELAY_BULK = 5000;
	/** If opennet is enabled, and there are fewer than this many connections,
	 * we MAY attempt to contact old opennet peers (opennet peers we have
	 * dropped from the routing table but kept around in case we can't connect). */
	static final int MIN_CONNECTIONS_TRY_OLD_OPENNET_PEERS = 5;
	/** We send connect attempts to old-opennet-peers no more than once every
	 * this many milliseconds. */
	static final int MIN_OLD_OPENNET_CONNECT_DELAY_NO_CONNS = 10 * 1000;
	/** We send connect attempts to old-opennet-peers no more than once every
	 * this many milliseconds. */
	static final int MIN_OLD_OPENNET_CONNECT_DELAY = 60 * 1000;
	final NativeThread myThread;
	final Node node;
	NodeStats stats;
	long lastReportedNoPackets;
	long lastReceivedPacketFromAnyNode;
	private MersenneTwister localRandom;

	PacketSender(Node node) {
		this.node = node;
		myThread = new NativeThread(this, "PacketSender thread for " + node.getDarknetPortNumber(), NativeThread.MAX_PRIORITY, false);
		myThread.setDaemon(true);
		localRandom = node.createRandom();
	}

	void start(NodeStats stats) {
		this.stats = stats;
		Logger.normal(this, "Starting PacketSender");
		System.out.println("Starting PacketSender");
		myThread.start();
	}

	private void schedulePeriodicJob() {
		
		node.ticker.queueTimedJob(new Runnable() {

			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					if (logMINOR)
						Logger.minor(PacketSender.class,
								"Starting shedulePeriodicJob() at " + now);
					PeerManager pm = node.peers;
					pm.maybeLogPeerNodeStatusSummary(now);
					pm.maybeUpdateOldestNeverConnectedDarknetPeerAge(now);
					stats.maybeUpdatePeerManagerUserAlertStats(now);
					stats.maybeUpdateNodeIOStats(now);
					pm.maybeUpdatePeerNodeRoutableConnectionStats(now);

					if (logMINOR)
						Logger.minor(PacketSender.class,
								"Finished running shedulePeriodicJob() at "
										+ System.currentTimeMillis());
				} finally {
					node.ticker.queueTimedJob(this, 1000);
				}
			}
		}, 1000);
	}

	@Override
	public void run() {
		if(logMINOR) Logger.minor(this, "In PacketSender.run()");
		freenet.support.Logger.OSThread.logPID(this);

                schedulePeriodicJob();
		/*
		 * Index of the point in the nodes list at which we sent a packet and then
		 * ran out of bandwidth. We start the loop from here next time.
		 */
		while(true) {
			lastReceivedPacketFromAnyNode = lastReportedNoPackets;
			try {
				realRun();
			} catch(OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("Will retry above failed operation...");
			} catch(Throwable t) {
				Logger.error(this, "Caught in PacketSender: " + t, t);
				System.err.println("Caught in PacketSender: " + t);
				t.printStackTrace();
			}
		}
	}

	private void realRun() {
		long now = System.currentTimeMillis();
                PeerManager pm;
		PeerNode[] nodes;

        pm = node.peers;
        nodes = pm.myPeers();

		long nextActionTime = Long.MAX_VALUE;
		long oldTempNow = now;

		boolean canSendThrottled = false;

		int MAX_PACKET_SIZE = node.darknetCrypto.socket.getMaxPacketSize();
		long count = node.outputThrottle.getCount();
		if(count > MAX_PACKET_SIZE)
			canSendThrottled = true;
		else {
			long canSendAt = node.outputThrottle.getNanosPerTick() * (MAX_PACKET_SIZE - count);
			canSendAt = (canSendAt / (1000*1000)) + (canSendAt % (1000*1000) == 0 ? 0 : 1);
			if(logMINOR)
				Logger.minor(this, "Can send throttled packets in "+canSendAt+"ms");
			nextActionTime = Math.min(nextActionTime, now + canSendAt);
		}
		
		/** The earliest time at which a peer-transport needs to send a packet, which is before
		 * now. Throttled if canSendThrottled, otherwise not throttled. */
		long lowestUrgentSendTime = Long.MAX_VALUE;
		/** The peer(s)-transport which lowestUrgentSendTime is referring to */
		ArrayList<PeerPacketTransport> urgentSendPeerTransports = null;
		/** The earliest time at which a peer-transport needs to send a packet, which is after
		 * now, where there is a full packet's worth of data to send. 
		 * Throttled if canSendThrottled, otherwise not throttled. */
		long lowestFullPacketSendTime = Long.MAX_VALUE;
		/** The peer(s)-transport which lowestFullPacketSendTime is referring to */
		ArrayList<PeerPacketTransport> urgentFullPacketPeerTransports = null;
		/** The earliest time at which a peer transport needs to send an ack, before now. */
		long lowestAckTime = Long.MAX_VALUE;
		/** The peer(s)-transport which lowestAckTime is referring to */
		ArrayList<PeerPacketTransport> ackPeerTransports = null;
		/** The earliest time at which a peer needs to handshake. */
		long lowestHandshakeTime = Long.MAX_VALUE;
		/** The peer(s)-transport which lowestHandshakeTime is referring to */
		ArrayList<PeerPacketTransport> handshakePeerTransports = null;

		for(int i = 0; i < nodes.length; i++) {
			now = System.currentTimeMillis();
			int idx = i;
			
			// Basic peer maintenance.
			
			PeerNode pn = nodes[idx];

			pn.maybeOnConnect();
			if(pn.shouldDisconnectAndRemoveNow() && !pn.isDisconnecting()) {
				// Might as well do it properly.
				node.peers.disconnectAndRemove(pn, true, true, false);
			}

			boolean noContacts = true;
			
			if(pn.isConnected()) {
				
				if(pn.isRoutable() && pn.noLongerRoutable()) {
					/*
					 NOTE: Whereas isRoutable() && noLongerRoutable() are generally mutually exclusive, this
					 code will only execute because of the scheduled-runnable in start() which executes
					 updateVersionRoutablity() on all our peers. We don't disconnect the peer, but mark it
					 as being incompatible.
					 */
					pn.invalidate(now);
					Logger.normal(this, "shouldDisconnectNow has returned true : marking the peer as incompatible: "+pn);
					continue;
				}
				
				HashMap<String, PeerPacketTransport> peerMap = pn.getPeerPacketTransportMap();
				for(String transportName : peerMap.keySet()) {
					
					PeerPacketTransport peerTransport = peerMap.get(transportName);
					// For purposes of detecting not having received anything, which indicates a 
					// serious connectivity problem, we want to look for *any* packets received, 
					// including auth packets.
					lastReceivedPacketFromAnyNode =
						Math.max(peerTransport.lastReceivedTransportPacketTime(), lastReceivedPacketFromAnyNode);
					if(peerTransport.isTransportConnected()) {
						
						boolean shouldThrottle = peerTransport.shouldThrottle();
						
						peerTransport.checkForLostPackets();
	
						// Is the transport dead?
						// It might be disconnected in terms of FNP but trying to reconnect via JFK's, so we need to use the time when we last got a *data* packet.
						if(now - peerTransport.lastReceivedTransportDataPacketTime() > peerTransport.pn.maxTimeBetweenReceivedPackets()) {
							Logger.normal(this, "Disconnecting from " + peerTransport + " - haven't received packets recently");
							peerTransport.disconnectTransport(false);
							continue;
						} else if(now - peerTransport.lastReceivedTransportAckTime() > peerTransport.pn.maxTimeBetweenReceivedAcks()) {
							Logger.normal(this, "Disconnecting from " + peerTransport + " - haven't received acks recently");
							peerTransport.disconnectTransport(true);
							continue;
						}
						// The peer is connected.
						
						if(canSendThrottled || !shouldThrottle) {
							// We can send to this peer.
							long sendTime = peerTransport.getNextUrgentTime(now);
							if(sendTime != Long.MAX_VALUE) {
								if(sendTime <= now) {
									// Message is urgent.
									if(sendTime < lowestUrgentSendTime) {
										lowestUrgentSendTime = sendTime;
										if(urgentSendPeerTransports != null)
											urgentSendPeerTransports.clear();
										else
											urgentSendPeerTransports = new ArrayList<PeerPacketTransport>();
									}
									if(sendTime <= lowestUrgentSendTime)
										urgentSendPeerTransports.add(peerTransport);
								} else if(peerTransport.fullPacketQueued()) {
									if(sendTime < lowestFullPacketSendTime) {
										lowestFullPacketSendTime = sendTime;
										if(urgentFullPacketPeerTransports != null)
											urgentFullPacketPeerTransports.clear();
										else
											urgentFullPacketPeerTransports = new ArrayList<PeerPacketTransport>();
									}
									if(sendTime <= lowestFullPacketSendTime)
										urgentFullPacketPeerTransports.add(peerTransport);
								}
							}
						} else if(shouldThrottle && !canSendThrottled) {
							long ackTime = peerTransport.timeSendAcks();
							if(ackTime != Long.MAX_VALUE) {
								if(ackTime <= now) {
									if(ackTime < lowestAckTime) {
										lowestAckTime = ackTime;
										if(ackPeerTransports != null)
											ackPeerTransports.clear();
										else
											ackPeerTransports = new ArrayList<PeerPacketTransport>();
									}
									if(ackTime <= lowestAckTime)
										ackPeerTransports.add(peerTransport);
								}
							}
						}
						
						if(canSendThrottled || !shouldThrottle) {
							long urgentTime = peerTransport.getNextUrgentTime(now);
							// Should spam the logs, unless there is a deadlock
							if(urgentTime < Long.MAX_VALUE && logMINOR)
								Logger.minor(this, "Next urgent time: " + urgentTime + "(in "+(urgentTime - now)+") for " + pn);
							nextActionTime = Math.min(nextActionTime, urgentTime);
						} else {
							nextActionTime = Math.min(nextActionTime, peerTransport.timeCheckForLostPackets());
						}
					}
					
					if(!peerTransport.noContactDetails()) {
						noContacts = false;
					}
					
					long handshakeTime = peerTransport.timeSendHandshake(now);
					if(handshakeTime != Long.MAX_VALUE) {
						if(handshakeTime < lowestHandshakeTime) {
							lowestHandshakeTime = handshakeTime;
							if(handshakePeerTransports != null)
								handshakePeerTransports.clear();
							else
								handshakePeerTransports = new ArrayList<PeerPacketTransport>();
						}
						if(handshakeTime <= lowestHandshakeTime)
							handshakePeerTransports.add(peerTransport);
					}
					
					long tempNow = System.currentTimeMillis();
					if((tempNow - oldTempNow) > (5 * 1000))
						Logger.error(this, "tempNow is more than 5 seconds past oldTempNow (" + (tempNow - oldTempNow) + ") in PacketSender working with " + pn.userToString());
					oldTempNow = tempNow;
				}
			} else
				// Not connected
				
			if(noContacts)
				pn.startARKFetcher();
		}
		
		// We may send a packet, send an ack-only packet, or send a handshake.
		
		PeerPacketTransport toSendPacket = null;
		PeerPacketTransport toSendAckOnly = null;
		PeerPacketTransport toSendHandshake = null;
		
		long t = Long.MAX_VALUE;
		
		if(lowestUrgentSendTime <= now) {
			// We need to send a full packet.
			toSendPacket = urgentSendPeerTransports.get(localRandom.nextInt(urgentSendPeerTransports.size()));
			t = lowestUrgentSendTime;
		} else if(lowestFullPacketSendTime < Long.MAX_VALUE) {
			toSendPacket = urgentFullPacketPeerTransports.get(localRandom.nextInt(urgentFullPacketPeerTransports.size()));
			t = lowestFullPacketSendTime;
		} else if(lowestAckTime <= now) {
			// We need to send an ack
			toSendAckOnly = ackPeerTransports.get(localRandom.nextInt(ackPeerTransports.size()));
			t = lowestAckTime;
		}
		
		if(lowestHandshakeTime <= now && t > lowestHandshakeTime) {
			toSendHandshake = handshakePeerTransports.get(localRandom.nextInt(handshakePeerTransports.size()));
			toSendPacket = null;
			toSendAckOnly = null;
		}
		
		if(toSendPacket != null) {
			try {
				if(toSendPacket.maybeSendPacket(now, false)) {
					count = node.outputThrottle.getCount();
					if(count > MAX_PACKET_SIZE)
						canSendThrottled = true;
					else {
						canSendThrottled = false;
						long canSendAt = node.outputThrottle.getNanosPerTick() * (MAX_PACKET_SIZE - count);
						canSendAt = (canSendAt / (1000*1000)) + (canSendAt % (1000*1000) == 0 ? 0 : 1);
						if(logMINOR)
							Logger.minor(this, "Can send throttled packets in "+canSendAt+"ms");
						nextActionTime = Math.min(nextActionTime, now + canSendAt);
					}
				}
			} catch (BlockedTooLongException e) {
				Logger.error(this, "Waited too long: "+TimeUtil.formatTime(e.delta)+" to allocate a packet number to send to "+toSendPacket+" : (new packet format)"+" (version "+toSendPacket.pn.getVersionNumber()+") - DISCONNECTING!");
				toSendPacket.disconnectTransport(true);
				onForceDisconnectBlockTooLong(toSendPacket, e);
			}

			if(canSendThrottled || !toSendPacket.shouldThrottle()) {
				long urgentTime = toSendPacket.getNextUrgentTime(now);
				// Should spam the logs, unless there is a deadlock
				if(urgentTime < Long.MAX_VALUE && logMINOR)
					Logger.minor(this, "Next urgent time: " + urgentTime + "(in "+(urgentTime - now)+") for " + toSendPacket);
				nextActionTime = Math.min(nextActionTime, urgentTime);
			} else {
				nextActionTime = Math.min(nextActionTime, toSendPacket.timeCheckForLostPackets());
			}

		} else if(toSendAckOnly != null) {
			try {
				if(toSendAckOnly.maybeSendPacket(now, true)) {
					count = node.outputThrottle.getCount();
					if(count > MAX_PACKET_SIZE)
						canSendThrottled = true;
					else {
						canSendThrottled = false;
						long canSendAt = node.outputThrottle.getNanosPerTick() * (MAX_PACKET_SIZE - count);
						canSendAt = (canSendAt / (1000*1000)) + (canSendAt % (1000*1000) == 0 ? 0 : 1);
						if(logMINOR)
							Logger.minor(this, "Can send throttled packets in "+canSendAt+"ms");
						nextActionTime = Math.min(nextActionTime, now + canSendAt);
					}
				}
			} catch (BlockedTooLongException e) {
				Logger.error(this, "Waited too long: "+TimeUtil.formatTime(e.delta)+" to allocate a packet number to send to "+toSendAckOnly+" : (new packet format)"+" (version "+toSendAckOnly.pn.getVersionNumber()+") - DISCONNECTING!");
				toSendAckOnly.disconnectTransport(true);
				onForceDisconnectBlockTooLong(toSendAckOnly, e);
			}

			if(canSendThrottled || !toSendAckOnly.shouldThrottle()) {
				long urgentTime = toSendAckOnly.getNextUrgentTime(now);
				// Should spam the logs, unless there is a deadlock
				if(urgentTime < Long.MAX_VALUE && logMINOR)
					Logger.minor(this, "Next urgent time: " + urgentTime + "(in "+(urgentTime - now)+") for " + toSendAckOnly);
				nextActionTime = Math.min(nextActionTime, urgentTime);
			} else {
				nextActionTime = Math.min(nextActionTime, toSendAckOnly.timeCheckForLostPackets());
			}
		}
		
		if(toSendHandshake != null) {
			// Send handshake if necessary
			long beforeHandshakeTime = System.currentTimeMillis();
			toSendHandshake.sendHandshake(false);
			long afterHandshakeTime = System.currentTimeMillis();
			if((afterHandshakeTime - beforeHandshakeTime) > (2 * 1000))
				Logger.error(this, "afterHandshakeTime is more than 2 seconds past beforeHandshakeTime (" + (afterHandshakeTime - beforeHandshakeTime) + ") in PacketSender working with " + toSendHandshake.userToString());
		}
		
		// All of these take into account whether the data can be sent already.
		// So we can include them in nextActionTime.
		nextActionTime = Math.min(nextActionTime, lowestUrgentSendTime);
		nextActionTime = Math.min(nextActionTime, lowestFullPacketSendTime);
		nextActionTime = Math.min(nextActionTime, lowestAckTime);
		nextActionTime = Math.min(nextActionTime, lowestHandshakeTime);

		// FIXME: If we send something we will have to go around the loop again.
		// OPTIMISATION: We could track the second best, and check how many are in the array.
		
		/* Attempt to connect to old-opennet-peers.
		 * Constantly send handshake packets, in order to get through a NAT.
		 * Most JFK(1)'s are less than 300 bytes. 25*300/15 = avg 500B/sec bandwidth cost.
		 * Well worth it to allow us to reconnect more quickly. */

		OpennetManager om = node.getOpennet();
		if(om != null && node.getUptime() > 30*1000) {
			PeerNode[] peers = om.getOldPeers();

			for(PeerNode pn : peers) {
				if(pn.timeLastConnected() <= 0)
					Logger.error(this, "Last connected is zero or negative for old-opennet-peer "+pn);
				// Will be removed by next line.
				if(now - pn.timeLastConnected() > OpennetManager.MAX_TIME_ON_OLD_OPENNET_PEERS) {
					om.purgeOldOpennetPeer(pn);
					if(logMINOR) Logger.minor(this, "Removing old opennet peer (too old): "+pn+" age is "+TimeUtil.formatTime(now - pn.timeLastConnected()));
					continue;
				}
				if(pn.isConnected()) continue; // Race condition??
				
				HashMap<String, PeerPacketTransport> peerMap = pn.getPeerPacketTransportMap();
				boolean noContacts = true;
				for(String transportName : peerMap.keySet()) {
					PeerPacketTransport peerTransport = peerMap.get(transportName);
					if(peerTransport.noContactDetails())
						continue;
					noContacts = false;
					if(peerTransport.shouldSendHandshake()) {
						// Send handshake if necessary
						long beforeHandshakeTime = System.currentTimeMillis();
						peerTransport.sendHandshake(true);
						long afterHandshakeTime = System.currentTimeMillis();
						if((afterHandshakeTime - beforeHandshakeTime) > (2 * 1000))
							Logger.error(this, "afterHandshakeTime is more than 2 seconds past beforeHandshakeTime (" + (afterHandshakeTime - beforeHandshakeTime) + ") in PacketSender working with " + pn.userToString());
					}
				}
				if(noContacts)
					pn.startARKFetcher();
			}

		}

		long oldNow = now;

		// Send may have taken some time
		now = System.currentTimeMillis();

		if((now - oldNow) > (10 * 1000))
			Logger.error(this, "now is more than 10 seconds past oldNow (" + (now - oldNow) + ") in PacketSender");

		long sleepTime = nextActionTime - now;
		
		// MAX_COALESCING_DELAYms maximum sleep time - same as the maximum coalescing delay
		sleepTime = Math.min(sleepTime, MAX_COALESCING_DELAY);

		if(now - node.startupTime > 60 * 1000 * 5)
			if(now - lastReceivedPacketFromAnyNode > Node.ALARM_TIME) {
				Logger.error(this, "Have not received any packets from any node in last " + Node.ALARM_TIME / 1000 + " seconds");
				lastReportedNoPackets = now;
			}

		if(sleepTime > 0) {
			// Update logging only when have time to do so
			try {
				if(logMINOR)
					Logger.minor(this, "Sleeping for " + sleepTime);
				synchronized(this) {
					wait(sleepTime);
				}
			} catch(InterruptedException e) {
			// Ignore, just wake up. Probably we got interrupt()ed
			// because a new packet came in.
			}
		} else {
			if(logDEBUG)
				Logger.debug(this, "Next urgent time is "+(now - nextActionTime)+"ms in the past");
		}
	}

	private final HashMap<String, HashSet<PluginAddress>> peersDumpedBlockedTooLong = new HashMap<String, HashSet<PluginAddress>>();

	private void onForceDisconnectBlockTooLong(PeerTransport peerTransport, BlockedTooLongException e) {
		PluginAddress addr = peerTransport.detectedTransportAddress;
		String transportName = peerTransport.transportName;
		synchronized(peersDumpedBlockedTooLong) {
			if(peersDumpedBlockedTooLong.containsKey(transportName)) {
				peersDumpedBlockedTooLong.get(transportName).add(addr);
			}
			else {
				HashSet<PluginAddress> addresses = new HashSet<PluginAddress> ();
				addresses.add(addr);
				peersDumpedBlockedTooLong.put(transportName, addresses);
			}
			if(peersDumpedBlockedTooLong.size() > 1) return;
		}
		if(node.clientCore == null || node.clientCore.alerts == null)
			return;
		// FIXME XXX: We have had this alert enabled for MONTHS which got us hundreds of bug reports about it. Unfortunately, nobody spend any work on fixing
		// the issue after the alert was added so I have disabled it to quit annoying our users. We should not waste their time if we don't do anything. xor
		// Notice that the same alert is commented out in FNPPacketMangler.
		// node.clientCore.alerts.register(peersDumpedBlockedTooLongAlert);
	}

	@SuppressWarnings("unused")
	private final UserAlert peersDumpedBlockedTooLongAlert = new AbstractUserAlert() {

        @Override
		public String anchor() {
			return "disconnectedStillNotAcked";
		}

        @Override
		public String dismissButtonText() {
			return null;
		}

        @Override
		public short getPriorityClass() {
			return UserAlert.ERROR;
		}

        @Override
		public String getShortText() {
			int sz;
			synchronized(peersDumpedBlockedTooLong) {
				sz = peersDumpedBlockedTooLong.size();
			}
			return l10n("somePeersDisconnectedBlockedTooLong", "count", Integer.toString(sz));
		}

        @Override
		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");
			ArrayList<String> addressWithTransport = new ArrayList<String> ();
			HashMap<String, HashSet<PluginAddress>> peersBlocked;
			synchronized(peersDumpedBlockedTooLong) {
				peersBlocked = peersDumpedBlockedTooLong;
			}
			for(String transportName : peersBlocked.keySet()) {
				HashSet<PluginAddress> addresses = peersBlocked.get(transportName);
				for(PluginAddress addr : addresses)
					addressWithTransport.add(transportName + ":" + addr);
			}
			NodeL10n.getBase().addL10nSubstitution(div,
			        "PacketSender.somePeersDisconnectedBlockedTooLongDetail",
			        new String[] { "count", "link" },
			        new HTMLNode[] { HTMLNode.text(addressWithTransport.size()),
			                HTMLNode.link(ExternalLinkToadlet.escape("https://bugs.freenetproject.org/"))});
			HTMLNode list = div.addChild("ul");
			for(String address : addressWithTransport) {
				list.addChild("li", address);
			}
			return div;
		}

        @Override
		public String getText() {
			StringBuilder sb = new StringBuilder();
			ArrayList<String> addressWithTransport = new ArrayList<String> ();
			HashMap<String, HashSet<PluginAddress>> peersBlocked;
			synchronized(peersDumpedBlockedTooLong) {
				peersBlocked = peersDumpedBlockedTooLong;
			}
			for(String transportName : peersBlocked.keySet()) {
				HashSet<PluginAddress> addresses = peersBlocked.get(transportName);
				for(PluginAddress addr : addresses)
					addressWithTransport.add(transportName + ":" + addr);
			}
			sb.append(l10n("somePeersDisconnectedStillNotAckedDetail",
					new String[] { "count", "link", "/link" },
					new String[] { Integer.toString(addressWithTransport.size()), "", "" } ));
			sb.append('\n');
			for(String address : addressWithTransport) {
				sb.append('\t');
				sb.append(address);
				sb.append('\n');
			}
			return sb.toString();
		}

        @Override
		public String getTitle() {
			return getShortText();
		}

        @Override
		public Object getUserIdentifier() {
			return PacketSender.this;
		}

        @Override
		public boolean isEventNotification() {
			return false;
		}

        @Override
		public boolean isValid() {
			return true;
		}

        @Override
		public void isValid(boolean validity) {
			// Ignore
		}

        @Override
		public void onDismiss() {
			// Ignore
		}

        @Override
		public boolean shouldUnregisterOnDismiss() {
			return false;
		}

        @Override
		public boolean userCanDismiss() {
			return false;
		}

	};

	/** Wake up, and send any queued packets. */
	void wakeUp() {
		// Wake up if needed
		synchronized(this) {
			notifyAll();
		}
	}

	protected String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("PacketSender."+key, patterns, values);
	}

	protected String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("PacketSender."+key, pattern, value);
	}
}
