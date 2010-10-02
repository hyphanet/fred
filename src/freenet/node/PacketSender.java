/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.Vector;

import freenet.io.comm.Peer;
import freenet.l10n.NodeL10n;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.Executor;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
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
public class PacketSender implements Runnable, Ticker {

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
	/** ~= Ticker :) */
	private final TreeMap<Long, Object> timedJobsByTime;
	final NativeThread myThread;
	final Node node;
	NodeStats stats;
	long lastClearedOldSwapChains;
	long lastReportedNoPackets;
	long lastReceivedPacketFromAnyNode;
	private Vector<ResendPacketItem> rpiTemp;
	private int[] rpiIntTemp;

	private final static class Job {
		final String name;
		final Runnable job;
		Job(String name, Runnable job) {
			this.name = name;
			this.job = job;
		}

		public boolean equals(Object o) {
			if(!(o instanceof Job)) return false;
			// Ignore the name, we are only interested in the job, needed for noDupes.
			return ((Job)o).job == job;
		}
	}

	PacketSender(Node node) {
		timedJobsByTime = new TreeMap<Long, Object>();
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
		long now = System.currentTimeMillis();
		long transition = Version.transitionTime();
		if(now < transition)
			queueTimedJob(new Runnable() {

					public void run() {
						freenet.support.Logger.OSThread.logPID(this);
						PeerNode[] nodes = node.peers.myPeers;
						for(int i = 0; i < nodes.length; i++) {
							PeerNode pn = nodes[i];
							pn.updateVersionRoutablity();
						}
					}
				}, transition - now);
		myThread.start();
	}

	public void run() {
		if(logMINOR) Logger.minor(this, "In PacketSender.run()");
		freenet.support.Logger.OSThread.logPID(this);
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
		PeerManager pm = node.peers;
		PeerNode[] nodes = pm.myPeers;
		// Run the time sensitive status updater separately
		for(int i = 0; i < nodes.length; i++) {
			PeerNode pn = nodes[i];
			// Only routing backed off nodes should need status updating since everything else
			// should get updated immediately when it's changed
			if(pn.getPeerNodeStatus() == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF)
				pn.setPeerNodeStatus(now);
		}
		pm.maybeLogPeerNodeStatusSummary(now);
		pm.maybeUpdateOldestNeverConnectedDarknetPeerAge(now);
		stats.maybeUpdatePeerManagerUserAlertStats(now);
		stats.maybeUpdateNodeIOStats(now);
		pm.maybeUpdatePeerNodeRoutableConnectionStats(now);
		long nextActionTime = Long.MAX_VALUE;
		long oldTempNow = now;
		// Needs to be run very frequently. Maybe change to a regular once per second schedule job?
		// Maybe not worth it as it is fairly lightweight.
		// FIXME given the lock contention, maybe it's worth it? What about
		// running it on the UdpSocketHandler thread? That would surely be better...?
		node.lm.removeTooOldQueuedItems();

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
			lastReceivedPacketFromAnyNode =
				Math.max(pn.lastReceivedPacketTime(), lastReceivedPacketFromAnyNode);
			pn.maybeOnConnect();
			if(pn.shouldDisconnectAndRemoveNow() && !pn.isDisconnecting()) {
				// Might as well do it properly.
				node.peers.disconnect(pn, true, true, false);
			}

			if(pn.isConnected()) {

				if(pn.shouldThrottle() && !canSendThrottled)
					continue;

				// Is the node dead?
				if(now - pn.lastReceivedPacketTime() > pn.maxTimeBetweenReceivedPackets()) {
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
				if((canSendThrottled || !pn.shouldThrottle()) && pn.maybeSendPacket(now, rpiTemp, rpiIntTemp)) {
					canSendThrottled = false;
					count = node.outputThrottle.getCount();
					if(count > MAX_PACKET_SIZE)
						canSendThrottled = true;
					else {
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

				long urgentTime = pn.getNextUrgentTime(now);
				// Should spam the logs, unless there is a deadlock
				if(urgentTime < Long.MAX_VALUE && logMINOR)
					Logger.minor(this, "Next urgent time: " + urgentTime + "(in "+(urgentTime - now)+") for " + pn.getPeer());
				nextActionTime = Math.min(nextActionTime, urgentTime);
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

		if(now - lastClearedOldSwapChains > 10000) {
			node.lm.clearOldSwapChains();
			lastClearedOldSwapChains = now;
		}

		long oldNow = now;

		// Send may have taken some time
		now = System.currentTimeMillis();

		if((now - oldNow) > (10 * 1000))
			Logger.error(this, "now is more than 10 seconds past oldNow (" + (now - oldNow) + ") in PacketSender");

		List<Job> jobsToRun = null;

		synchronized(timedJobsByTime) {
			while(!timedJobsByTime.isEmpty()) {
				Long tRun = timedJobsByTime.firstKey();
				if(tRun.longValue() <= now) {
					if(jobsToRun == null)
						jobsToRun = new ArrayList<Job>();
					Object o = timedJobsByTime.remove(tRun);
					if(o instanceof Job[]) {
						Job[] r = (Job[]) o;
						for(int i = 0; i < r.length; i++)
							jobsToRun.add(r[i]);
					} else {
						Job r = (Job) o;
						jobsToRun.add(r);
					}
				} else
					// FIXME how accurately do we want ticker jobs to be scheduled?
					// FIXME can they wait the odd 200ms?

					break;
			}
		}

		if(jobsToRun != null)
			for(Job r : jobsToRun) {
				if(logMINOR)
					Logger.minor(this, "Running " + r);
				if(r.job instanceof FastRunnable)
					// Run in-line

					try {
						r.job.run();
					} catch(Throwable t) {
						Logger.error(this, "Caught " + t + " running " + r, t);
					}
				else
					try {
						node.executor.execute(r.job, r.name, true);
					} catch(OutOfMemoryError e) {
						OOMHandler.handleOOM(e);
						System.err.println("Will retry above failed operation...");
						queueTimedJob(r.job, r.name, 200, true, false);
					} catch(Throwable t) {
						Logger.error(this, "Caught in PacketSender: " + t, t);
						System.err.println("Caught in PacketSender: " + t);
						t.printStackTrace();
					}
			}

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

	private HashSet<Peer> peersDumpedBlockedTooLong = new HashSet<Peer>();

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
	private UserAlert peersDumpedBlockedTooLongAlert = new AbstractUserAlert() {

		public String anchor() {
			return "disconnectedStillNotAcked";
		}

		public String dismissButtonText() {
			return null;
		}

		public short getPriorityClass() {
			return UserAlert.ERROR;
		}

		public String getShortText() {
			int sz;
			synchronized(peersDumpedBlockedTooLong) {
				sz = peersDumpedBlockedTooLong.size();
			}
			return l10n("somePeersDisconnectedBlockedTooLong", "count", Integer.toString(sz));
		}

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

		public String getText() {
			StringBuffer sb = new StringBuffer();
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

		public String getTitle() {
			return getShortText();
		}

		public Object getUserIdentifier() {
			return PacketSender.this;
		}

		public boolean isEventNotification() {
			return false;
		}

		public boolean isValid() {
			return true;
		}

		public void isValid(boolean validity) {
			// Ignore
		}

		public void onDismiss() {
			// Ignore
		}

		public boolean shouldUnregisterOnDismiss() {
			return false;
		}

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

	public void queueTimedJob(Runnable job, long offset) {
		queueTimedJob(job, "Scheduled job: "+job, offset, false, false);
	}

	/**
	 * Queue a job at a specific time.
	 * @param runner The job to run. FastRunnable's get run directly on the PacketSender thread.
	 * @param name The name of the job, the thread running it will temporarily take this name,
	 * assuming it is run on a separate thread.
	 * @param offset The time at which to run the job in milliseconds after
	 * System.currentTimeMillis().
	 * @param runOnTickerAnyway If false, run jobs with offset <=0 on the ticker, to preserve
	 * their thread priorities; if true, jobs to run immediately through the executor (which
	 * normally will also preserve thread priorities, but may need to call back via
	 * runOnTickerAnyway=true if it needs to increase the thread priority).
	 * @param noDupes Don't run this job if it is already scheduled. Relatively expensive, O(n)
	 * with queued jobs. Necessary for Announcer to ensure that we don't get exponentially
	 * increasing numbers of announcement check jobs queued, while ensuring that we do always
	 * have one queued within the given period.
	 */
	public void queueTimedJob(Runnable runner, String name, long offset, boolean runOnTickerAnyway, boolean noDupes) {
		// Run directly *if* that won't cause any priority problems.
		if(offset <= 0 && !runOnTickerAnyway) {
			if(logMINOR) Logger.minor(this, "Running directly: "+runner);
			node.executor.execute(runner, name);
			return;
		}
		Job job = new Job(name, runner);
		if(offset < 0) offset = 0;
		long now = System.currentTimeMillis();
		Long l = Long.valueOf(offset + now);
		synchronized(timedJobsByTime) {
			if(noDupes) {
				if(timedJobsByTime.containsValue(new Job(name, runner))) {
					// O(n) but this is okay as it is only used by Announcer.
					// If you replace this with a lookup set, be *very* careful to ensure that the two are consistent,
					// otherwise we can forget important recurring jobs e.g. Announcer.
					Logger.normal(this, "Not re-running as already queued: "+runner+" for "+name);
					return;
				}
			}
			Object o = timedJobsByTime.get(l);
			if(o == null)
				timedJobsByTime.put(l, job);
			else if(o instanceof Job)
				timedJobsByTime.put(l, new Job[]{(Job) o, job});
			else if(o instanceof Job[]) {
				Job[] r = (Job[]) o;
				Job[] jobs = new Job[r.length + 1];
				System.arraycopy(r, 0, jobs, 0, r.length);
				jobs[jobs.length - 1] = job;
				timedJobsByTime.put(l, jobs);
			}
		}
		if(offset < MAX_COALESCING_DELAY) {
			wakeUp();
		}
	}

	public Executor getExecutor() {
		return node.executor;
	}
}
