/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashSet;
import java.util.Vector;

import freenet.io.comm.Peer;
import freenet.l10n.NodeL10n;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.OOMHandler;
import freenet.support.TimeUtil;
import freenet.support.io.NativeThread;

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
	private Vector<ResendPacketItem> rpiTemp;
	private int[] rpiIntTemp;

	PacketSender(Node node) {
		this.node = node;
		myThread = new NativeThread(this, "PacketSender thread for " + node.getDarknetPortNumber(), NativeThread.MAX_PRIORITY, false);
		myThread.setDaemon(true);
		rpiTemp = new Vector<ResendPacketItem>();
		rpiIntTemp = new int[64];
	}

	void start(NodeStats stats) {
		this.stats = stats;
		Logger.normal(this, "Starting PacketSender");
		System.out.println("Starting PacketSender");
		myThread.start();
	}

	private void schedulePeriodicJob() {
		
		node.ticker.queueTimedJob(new Runnable() {

			public void run() {
				try {
					node.lm.clearOldSwapChains();
				} finally {
					node.ticker.queueTimedJob(this, 10*1000);
				}
			}
			
		}, 10*1000);
		
		node.ticker.queueTimedJob(new Runnable() {

			public void run() {
				try {
					long now = System.currentTimeMillis();
					if (logMINOR)
						Logger.minor(PacketSender.class,
								"Starting shedulePeriodicJob() at " + now);
					PeerManager pm;
					PeerNode[] nodes;

					synchronized (PacketSender.class) {
						pm = node.peers;
						nodes = pm.myPeers;
					}
					// Run the time sensitive status updater separately
					for (int i = 0; i < nodes.length; i++) {
						PeerNode pn = nodes[i];
						// Only routing backed off nodes should need status
						// updating since everything else
						// should get updated immediately when it's changed
						if (pn.getPeerNodeStatus() == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF) {
							pn.setPeerNodeStatus(now);
						}
					}
					pm.maybeLogPeerNodeStatusSummary(now);
					pm.maybeUpdateOldestNeverConnectedDarknetPeerAge(now);
					stats.maybeUpdatePeerManagerUserAlertStats(now);
					stats.maybeUpdateNodeIOStats(now);
					pm.maybeUpdatePeerNodeRoutableConnectionStats(now);
					node.lm.removeTooOldQueuedItems();

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

	public void run() {
		if(logMINOR) Logger.minor(this, "In PacketSender.run()");
		freenet.support.Logger.OSThread.logPID(this);

                schedulePeriodicJob();
		/*
		 * Index of the point in the nodes list at which we sent a packet and then
		 * ran out of bandwidth. We start the loop from here next time.
		 */
		int brokeAt = 0;
		while(true) {
			lastReceivedPacketFromAnyNode = lastReportedNoPackets;
			try {
				brokeAt = realRun(brokeAt);
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

	private int realRun(int brokeAt) {
		long now = System.currentTimeMillis();
                PeerManager pm;
		PeerNode[] nodes;

                synchronized (PacketSender.class) {
                    pm = node.peers;
                    nodes = pm.myPeers;
                }

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

		int newBrokeAt = brokeAt;
		for(int i = 0; i < nodes.length; i++) {
			int idx = (i + brokeAt + 1) % nodes.length;
			PeerNode pn = nodes[idx];
                        final long lastReceivedPacketTime = pn.lastReceivedPacketTime();
			lastReceivedPacketFromAnyNode =
				Math.max(lastReceivedPacketTime, lastReceivedPacketFromAnyNode);
			pn.maybeOnConnect();
			if(pn.shouldDisconnectAndRemoveNow() && !pn.isDisconnecting()) {
				// Might as well do it properly.
				node.peers.disconnect(pn, true, true, false);
			}

			if(pn.isConnected()) {
				
				boolean shouldThrottle = pn.shouldThrottle();

				if(shouldThrottle && !canSendThrottled)
					continue;

				// Is the node dead?
				if(now - lastReceivedPacketTime > pn.maxTimeBetweenReceivedPackets()) {
					Logger.normal(this, "Disconnecting from " + pn + " - haven't received packets recently");
					pn.disconnected(false, false /* hopefully will recover, transient network glitch */);
					continue;
				} else if(pn.isRoutable() && pn.noLongerRoutable()) {
					/*
					 NOTE: Whereas isRoutable() && noLongerRoutable() are generally mutually exclusive, this
					 code will only execute because of the scheduled-runnable in start() which executes
					 updateVersionRoutablity() on all our peers. We don't disconnect the peer, but mark it
					 as being incompatible.
					 */
					pn.invalidate();
					pn.setPeerNodeStatus(now);
					Logger.normal(this, "shouldDisconnectNow has returned true : marking the peer as incompatible: "+pn);
					continue;
				}

				try {
					if(pn.maybeSendPacket(now, rpiTemp, rpiIntTemp)) {
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
							newBrokeAt = idx;
						}
					}
				} catch (BlockedTooLongException e) {
					Logger.error(this, "Waited too long: "+TimeUtil.formatTime(e.delta)+" to allocate a packet number to send to "+this+" on "+e.tracker+" - DISCONNECTING!");
					pn.forceDisconnect(true);
					onForceDisconnectBlockTooLong(pn, e);
				}

				if(canSendThrottled || !shouldThrottle) {
					long urgentTime = pn.getNextUrgentTime(now);
					// Should spam the logs, unless there is a deadlock
					if(urgentTime < Long.MAX_VALUE && logMINOR)
						Logger.minor(this, "Next urgent time: " + urgentTime + "(in "+(urgentTime - now)+") for " + pn.getPeer());
					nextActionTime = Math.min(nextActionTime, urgentTime);
				}
			} else
				// Not connected

				if(pn.noContactDetails())
					pn.startARKFetcher();

			if(pn.shouldSendHandshake()) {
				// Send handshake if necessary
				long beforeHandshakeTime = System.currentTimeMillis();
				pn.getOutgoingMangler().sendHandshake(pn, false);
				long afterHandshakeTime = System.currentTimeMillis();
				if((afterHandshakeTime - beforeHandshakeTime) > (2 * 1000))
					Logger.error(this, "afterHandshakeTime is more than 2 seconds past beforeHandshakeTime (" + (afterHandshakeTime - beforeHandshakeTime) + ") in PacketSender working with " + pn.userToString());
			}
			long tempNow = System.currentTimeMillis();
			if((tempNow - oldTempNow) > (5 * 1000))
				Logger.error(this, "tempNow is more than 5 seconds past oldTempNow (" + (tempNow - oldTempNow) + ") in PacketSender working with " + pn.userToString());
			oldTempNow = tempNow;
		}
		brokeAt = newBrokeAt;

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
					if(logMINOR) Logger.minor(this, "Removing old opennet peer (too old): "+pn);
					continue;
				}
				if(pn.isConnected()) continue; // Race condition??
				if(pn.noContactDetails()) {
					pn.startARKFetcher();
					continue;
				}
				if(pn.shouldSendHandshake()) {
					// Send handshake if necessary
					long beforeHandshakeTime = System.currentTimeMillis();
					pn.getOutgoingMangler().sendHandshake(pn, true);
					long afterHandshakeTime = System.currentTimeMillis();
					if((afterHandshakeTime - beforeHandshakeTime) > (2 * 1000))
						Logger.error(this, "afterHandshakeTime is more than 2 seconds past beforeHandshakeTime (" + (afterHandshakeTime - beforeHandshakeTime) + ") in PacketSender working with " + pn.userToString());
				}
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
		}
		return brokeAt;
	}

	private final HashSet<Peer> peersDumpedBlockedTooLong = new HashSet<Peer>();

	private void onForceDisconnectBlockTooLong(PeerNode pn, BlockedTooLongException e) {
		Peer p = pn.getPeer();
		synchronized(peersDumpedBlockedTooLong) {
			peersDumpedBlockedTooLong.add(p);
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
			Peer[] peers;
			synchronized(peersDumpedBlockedTooLong) {
				peers = peersDumpedBlockedTooLong.toArray(new Peer[peersDumpedBlockedTooLong.size()]);
			}
			NodeL10n.getBase().addL10nSubstitution(div, "PacketSender.somePeersDisconnectedBlockedTooLongDetail",
					new String[] { "count", "link", "/link" }
					, new String[] { Integer.toString(peers.length), "<a href=\"/?_CHECKED_HTTP_=https://bugs.freenetproject.org/\">", "</a>" });
			HTMLNode list = div.addChild("ul");
			for(Peer peer : peers) {
				list.addChild("li", peer.toString());
			}
			return div;
		}

        @Override
		public String getText() {
			StringBuilder sb = new StringBuilder();
			Peer[] peers;
			synchronized(peersDumpedBlockedTooLong) {
				peers = peersDumpedBlockedTooLong.toArray(new Peer[peersDumpedBlockedTooLong.size()]);
			}
			sb.append(l10n("somePeersDisconnectedStillNotAckedDetail",
					new String[] { "count", "link", "/link" },
					new String[] { Integer.toString(peers.length), "", "" } ));
			sb.append('\n');
			for(Peer peer : peers) {
				sb.append('\t');
				sb.append(peer.toString());
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
