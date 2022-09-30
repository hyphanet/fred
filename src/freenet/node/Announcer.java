/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.l10n.NodeL10n;
import freenet.node.useralerts.AbstractUserEvent;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserEvent;
import freenet.support.ByteArrayWrapper;
import freenet.support.HTMLNode;
import freenet.support.ListUtils;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.io.Closer;
import freenet.support.transport.ip.IPUtil;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Decide whether to announce, and announce if necessary to a node in the
 * routing table, or to a seednode.
 * @author toad
 */
public class Announcer {
	private static boolean logMINOR;
	private final Node node;
	private final OpennetManager om;
	private static final int STATUS_LOADING = 0;
	private static final int STATUS_CONNECTING_SEEDNODES = 1;
	private static final int STATUS_NO_SEEDNODES = -1;
	private int runningAnnouncements;
	/** We want to announce to 5 different seednodes. */
	private static final int WANT_ANNOUNCEMENTS = 5;
	private int sentAnnouncements;
	private long startTime;
	private long timeAddedSeeds;
	private static final long MIN_ADDED_SEEDS_INTERVAL = SECONDS.toMillis(60);
	/** After we have sent 3 announcements, wait for 30 seconds before sending 3 more if we still have no connections. */
	static final long COOLING_OFF_PERIOD = SECONDS.toMillis(30);
	/** Pubkey hashes of nodes we have announced to */
	private final HashSet<ByteArrayWrapper> announcedToIdentities;
	/** IPs of nodes we have announced to. Maybe this should be first-two-bytes, but I'm not sure how to do that with IPv6. */
	private final HashSet<InetAddress> announcedToIPs;
	/** How many nodes to connect to at once? */
	private static final int CONNECT_AT_ONCE = 15;
	/** Do not announce if there are more than this many opennet peers connected */
	private static final int MIN_OPENNET_CONNECTED_PEERS = 10;
	private static final long NOT_ALL_CONNECTED_DELAY = SECONDS.toMillis(60);
	private static final long RETRY_MISSING_SEEDNODES_DELAY = SECONDS.toMillis(30);
	/** Total nodes added by announcement so far */
	private int announcementAddedNodes;
	/** Total nodes that didn't want us so far */
	private int announcementNotWantedNodes;

	Announcer(OpennetManager om) {
		this.om = om;
		this.node = om.node;
		announcedToIdentities = new HashSet<ByteArrayWrapper>();
		announcedToIPs = new HashSet<InetAddress>();
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
	}

	protected void start() {
		if(!node.isOpennetEnabled()) return;
		int darkPeers = node.peers.getDarknetPeers().length;
		int openPeers = node.peers.getOpennetPeers().length;
		int oldOpenPeers = om.countOldOpennetPeers();
		if(darkPeers + openPeers + oldOpenPeers == 0) {
			// We know opennet is enabled.
			// We have no peers AT ALL.
			// So lets connect to a few seednodes, and attempt an announcement.
			System.err.println("Attempting announcement to seednodes...");
			synchronized(this) {
				registerEvent(STATUS_LOADING);
				started = true;
			}
			connectSomeSeednodes();
		} else {
			System.out.println("Not attempting immediate announcement: dark peers="+darkPeers+" open peers="+openPeers+" old open peers="+oldOpenPeers+" - will wait 1 minute...");
			// Wait a minute, then check whether we need to seed.
			node.getTicker().queueTimedJob(new Runnable() {
				@Override
				public void run() {
					synchronized(Announcer.this) {
						started = true;
					}
					try {
						maybeSendAnnouncement();
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t+" trying to send announcements", t);
					}
				}
			}, MIN_ADDED_SEEDS_INTERVAL);
		}
	}

	private void registerEvent(int eventStatus) {
		node.clientCore.alerts.register(new AnnouncementUserEvent(eventStatus));
	}

	private void connectSomeSeednodes() {
		if(!node.isOpennetEnabled()) return;
		boolean announceNow = false;
		if(logMINOR)
			Logger.minor(this, "Connecting some seednodes...");
		List<SimpleFieldSet> seeds = Announcer.readSeednodes(NodeFile.Seednodes.getFile(node));
		System.out.println("Trying to connect to "+seeds.size()+" seednodes...");
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(now - timeAddedSeeds < MIN_ADDED_SEEDS_INTERVAL) return;
			timeAddedSeeds = now;
			if(seeds.size() == 0) {
				registerEvent(STATUS_NO_SEEDNODES);
        /*
         * Developers might run nodes in empty directories instead of one made by an installer.
         * They can copy in the seed nodes file, so check for it periodically to support loading it
         * without the need to restart the node.
         *
         * TODO: If the seed nodes file is found it does not unregister the STATUS_NO_SEEDNODES
         * event.
         */
				node.getTicker().queueTimedJob(new Runnable() {
					public void run() {
						maybeSendAnnouncement();
					}
				}, Announcer.RETRY_MISSING_SEEDNODES_DELAY);
				return;
			} else {
				registerEvent(STATUS_CONNECTING_SEEDNODES);
			}
		}
		// Try to connect to some seednodes.
		// Once they are connected they will report back and we can attempt an announcement.

		int count = connectSomeNodesInner(seeds);
		boolean stillConnecting = false;
		List<SeedServerPeerNode> tryingSeeds = node.peers.getSeedServerPeersVector();
		synchronized(this) {
			for(SeedServerPeerNode seed : tryingSeeds) {
				if(!announcedToIdentities.contains(new ByteArrayWrapper(seed.peerECDSAPubKeyHash))) {
					// Either:
					// a) we are still trying to connect to this node,
					// b) there is a race condition and we haven't sent the announcement yet despite connecting, or
					// c) something is severely broken and we didn't send an announcement.
					// In any of these cases, we want to delay for 1 minute before resetting the connection process and connecting to everyone.
					stillConnecting = true;
					break;
				}
			}
			if(logMINOR)
				Logger.minor(this, "count = "+count+
						" announced = "+announcedToIdentities.size()+" running = "+runningAnnouncements+" still connecting "+stillConnecting);
			if(count == 0 && runningAnnouncements == 0) {
				// No more peers to connect to, and no announcements running.
				// Are there any peers which we are still trying to connect to?
				if(stillConnecting) {
					// Give them another minute.
					if(logMINOR)
						Logger.minor(this, "Will clear announced-to in 1 minute...");
					node.getTicker().queueTimedJob(new Runnable() {
						@Override
						public void run() {
							if(logMINOR)
								Logger.minor(this, "Clearing old announced-to list");
							synchronized(Announcer.this) {
								if(runningAnnouncements != 0) return;
								announcedToIdentities.clear();
								announcedToIPs.clear();
							}
							maybeSendAnnouncement();
						}
					}, NOT_ALL_CONNECTED_DELAY);
				} else {
					// We connected to all the seeds.
					// No point waiting!
					announcedToIdentities.clear();
					announcedToIPs.clear();
					announceNow = true;
				}
			}
		}
		node.dnsr.forceRun();
		// If none connect in a minute, try some more.
		node.getTicker().queueTimedJob(new Runnable() {
			@Override
			public void run() {
				try {
					maybeSendAnnouncement();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" trying to send announcements", t);
				}
			}
		}, announceNow ? 0 : MIN_ADDED_SEEDS_INTERVAL);
	}

	// Synchronize to protect announcedToIdentities and prevent running in parallel.
	private synchronized int connectSomeNodesInner(List<SimpleFieldSet> seeds) {
		if(logMINOR)
			Logger.minor(this, "Connecting some seednodes from "+seeds.size());
		int count = 0;
		while(count < CONNECT_AT_ONCE) {
			if(seeds.isEmpty()) break;
			SimpleFieldSet fs = ListUtils.removeRandomBySwapLastSimple(node.random, seeds);
			try {
				SeedServerPeerNode seed =
					new SeedServerPeerNode(fs, node, om.crypto, false);
				if(node.wantAnonAuth(true) && Arrays.equals(node.getOpennetPubKeyHash(), seed.peerECDSAPubKeyHash)) {
                                    if(logMINOR)
                                        Logger.minor("Not adding: I am a seednode attempting to connect to myself!", seed.userToString());
                                    continue;
                                }
                                if(announcedToIdentities.contains(new ByteArrayWrapper(seed.peerECDSAPubKeyHash))) {
					if(logMINOR)
						Logger.minor(this, "Not adding: already announced-to: "+seed.userToString());
					continue;
				}
				if(logMINOR)
					Logger.minor(this, "Trying to connect to seednode "+seed);
				if(node.peers.addPeer(seed)) {
					count++;
					if(logMINOR)
						Logger.minor(this, "Connecting to seednode "+seed);
				} else {
					if(logMINOR)
						Logger.minor(this, "Not connecting to seednode "+seed);
				}
			} catch (FSParseException e) {
				Logger.error(this, "Invalid seed in file: "+e+" for\n"+fs, e);
				continue;
			} catch (PeerParseException e) {
				Logger.error(this, "Invalid seed in file: "+e+" for\n"+fs, e);
				continue;
			} catch (ReferenceSignatureVerificationException e) {
				Logger.error(this, "Invalid seed in file: "+e+" for\n"+fs, e);
				continue;
			} catch (PeerTooOldException e) {
                Logger.error(this, "Invalid seed in file: "+e+" for\n"+fs, e);
                continue;
            }
		}
		if(logMINOR) Logger.minor(this, "connectSomeNodesInner() returning "+count);
		return count;
	}

	public static List<SimpleFieldSet> readSeednodes(File file) {
		List<SimpleFieldSet> list = new ArrayList<SimpleFieldSet>();
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			BufferedInputStream bis = new BufferedInputStream(fis);
			InputStreamReader isr = new InputStreamReader(bis, "UTF-8");
			BufferedReader br = new BufferedReader(isr);
			while(true) {
				try {
					SimpleFieldSet fs = new SimpleFieldSet(br, false, false, true, false);
					if(!fs.isEmpty())
						list.add(fs);
				} catch (EOFException e) {
					return list;
				} catch (IOException e) {
					Logger.error(Announcer.class, "Error while reading seednodes from " + file, e);
					// Continue reading. If this entry failed, we still want the following noderefs.
					// Read a line to advance the parsing position and avoid an endless loop.
					br.readLine();
				}
			}
		} catch (IOException e) {
			Logger.error(Announcer.class, "Unexpected error while reading seednodes from " + file, e);
			return list;
		} finally {
			Closer.close(fis);
		}
	}

	protected void stop() {
		// Do nothing at present
	}

	private long timeGotEnoughPeers = -1;
	private final Object timeGotEnoughPeersLock = new Object();
	private boolean killedAnnouncementTooOld;

	public int getAnnouncementThreshold() {
		// First, do we actually need to announce?
		int target = Math.min(MIN_OPENNET_CONNECTED_PEERS, om.getNumberOfConnectedPeersToAimIncludingDarknet() / 2);
		return target;
	}

	private SimpleUserAlert announcementDisabledAlert = 
		new SimpleUserAlert(false, l10n("announceDisabledTooOldTitle"), l10n("announceDisabledTooOld"), l10n("announceDisabledTooOldShort"), UserAlert.CRITICAL_ERROR) {
		
		@Override
		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");
			div.addChild("#", l10n("announceDisabledTooOld"));
			if(!node.nodeUpdater.isEnabled()) {
				div.addChild("#", " ");
				NodeL10n.getBase().addL10nSubstitution(div, "Announcer.announceDisabledTooOldUpdateDisabled", new String[] { "config" }, new HTMLNode[] { HTMLNode.link("/config/node.updater") });
			}
			// No point with !armed() or blown() because they have their own messages.
			return div;
		}
		
		@Override
		public String getText() {
			StringBuilder sb = new StringBuilder();
			sb.append(l10n("announceDisabledTooOld"));
			sb.append(" ");
			if(!node.nodeUpdater.isEnabled()) {
				sb.append(l10n("announceDisabledTooOldUpdateDisabled", new String[] { "config", "/config" }, new String[] { "", "" }));
			}
			return sb.toString();
		}
		
		@Override
		public boolean isValid() {
			if(node.nodeUpdater.isEnabled()) return false;
			// If it is enabled but not armed there will be a message from the updater.
			synchronized(Announcer.this) {
				return killedAnnouncementTooOld;
			}
		}
		
	};

	
	/** @return True if we have enough peers that we don't need to announce. */
	boolean enoughPeers() {
		if(om.stopping()) return true;
		// Do we want to send an announcement to the node?
		int opennetCount = node.peers.countConnectedPeers();
		int target = getAnnouncementThreshold();
		if(opennetCount >= target) {
			if(logMINOR)
				Logger.minor(this, "We have enough opennet peers: "+opennetCount+" > "+target+" since "+(System.currentTimeMillis()-timeGotEnoughPeers)+" ms");
			synchronized(timeGotEnoughPeersLock) {
				if(timeGotEnoughPeers <= 0)
					timeGotEnoughPeers = System.currentTimeMillis();
			}
			return true;
		}
		boolean killAnnouncement = false;
		if((!node.nodeUpdater.isEnabled()) ||
				(node.nodeUpdater.canUpdateNow() && !node.nodeUpdater.isArmed())) {
			// If we also have 10 TOO_NEW peers, we should shut down the announcement,
			// because we're obviously broken and would only be spamming the seednodes
			synchronized(this) {
				// Once we have shut down announcement, this persists until the auto-updater
				// is enabled.
				if(killedAnnouncementTooOld) return true;
			}
			if(node.peers.getPeerNodeStatusSize(PeerManager.PEER_NODE_STATUS_TOO_NEW, false) > 10) {
				synchronized(this) {
					if(killedAnnouncementTooOld) return true;
					killedAnnouncementTooOld = true;
					killAnnouncement = true;
				}
				Logger.error(this, "Shutting down announcement as we are older than the current mandatory build and auto-update is disabled or waiting for user input.");
				System.err.println("Shutting down announcement as we are older than the current mandatory build and auto-update is disabled or waiting for user input.");
				if(node.clientCore != null)
					node.clientCore.alerts.register(announcementDisabledAlert);
			}

		}
		
		if(killAnnouncement) {
			node.executor.execute(new Runnable() {

				@Override
				public void run() {
					for(OpennetPeerNode pn : node.peers.getOpennetPeers()) {
						node.peers.disconnectAndRemove(pn, true, true, true);
					}
					for(SeedServerPeerNode pn : node.peers.getSeedServerPeersVector()) {
						node.peers.disconnectAndRemove(pn, true, true, true);
					}
				}
				
			});
			return true;
		} else {
			synchronized(this) {
				killedAnnouncementTooOld = false;
			}
			if(node.clientCore != null)
				node.clientCore.alerts.unregister(announcementDisabledAlert);
			if(node.nodeUpdater.isEnabled() && node.nodeUpdater.isArmed() &&
					node.nodeUpdater.uom.fetchingFromTwo() &&
					node.peers.getPeerNodeStatusSize(PeerManager.PEER_NODE_STATUS_TOO_NEW, false) > 5) {
				// No point announcing at the moment, but we might need to if a transfer falls through.
				return true;
			}
		}
		
		synchronized(timeGotEnoughPeersLock) {
			timeGotEnoughPeers = -1;
		}
		return false;
	}

	/**
	 * Get the earliest time at which we had enough opennet peers. This is reset when we drop
	 * below the threshold.
	 */
	long timeGotEnoughPeers() {
		synchronized(timeGotEnoughPeersLock) {
			return timeGotEnoughPeers;
		}
	}

	/** 1 minute after we have enough peers, remove all seednodes left (presumably disconnected ones) */
	private static final long FINAL_DELAY = SECONDS.toMillis(60);
	/** But if we don't have enough peers at that point, wait another minute and if the situation has not improved, reannounce. */
	static final long RETRY_DELAY = SECONDS.toMillis(60);
	private boolean started = false;

	private final Runnable checker = new Runnable() {

		@Override
		public void run() {
			int running;
			synchronized(Announcer.this) {
				running = runningAnnouncements;
			}
			if(enoughPeers()) {
				for(SeedServerPeerNode pn : node.peers.getConnectedSeedServerPeersVector(null)) {
					node.peers.disconnectAndRemove(pn, true, true, false);
				}
				// Re-check every minute. Something bad might happen (e.g. cpu starvation), causing us to have to reseed.
				node.getTicker().queueTimedJob(new Runnable() {
					@Override
					public void run() {
						maybeSendAnnouncement();
					}
				}, "Check whether we need to announce", RETRY_DELAY, false, true);
			} else {
				node.getTicker().queueTimedJob(new Runnable() {
					@Override
					public void run() {
						maybeSendAnnouncement();
					}
				}, "Check whether we need to announce", RETRY_DELAY, false, true);
				if(running != 0)
					maybeSendAnnouncement();
			}
		}

	};

	public void maybeSendAnnouncementOffThread() {
		if(enoughPeers()) return;
		node.getTicker().queueTimedJob(new Runnable() {

			@Override
			public void run() {
				maybeSendAnnouncement();
			}

		}, 0);
	}

	protected void maybeSendAnnouncement() {
		synchronized(this) {
			if(!started) return;
		}
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "maybeSendAnnouncement()");
		long now = System.currentTimeMillis();
		if(!node.isOpennetEnabled()) return;
		if(enoughPeers()) {
			// Check again in 60 seconds.
			node.getTicker().queueTimedJob(checker, "Announcement checker", FINAL_DELAY, false, true);
			return;
		}
		synchronized(this) {
			// Double check after taking the lock.
			if(enoughPeers()) {
				// Check again in 60 seconds.
				node.getTicker().queueTimedJob(checker, "Announcement checker", FINAL_DELAY, false, true);
				return;
			}
			// Second, do we have many announcements running?
			if(runningAnnouncements > WANT_ANNOUNCEMENTS) {
				if(logMINOR)
					Logger.minor(this, "Running announcements already");
				return;
			}
			// In cooling-off period?
			if(System.currentTimeMillis() < startTime) {
				if(logMINOR)
					Logger.minor(this, "In cooling-off period for next "+TimeUtil.formatTime(startTime - System.currentTimeMillis()));
				return;
			}
			if(sentAnnouncements >= WANT_ANNOUNCEMENTS) {
				if(logMINOR)
					Logger.minor(this, "Sent enough announcements");
				return;
			}
			// Now find a node to announce to
			List<SeedServerPeerNode> seeds = node.peers.getConnectedSeedServerPeersVector(announcedToIdentities);
			while(sentAnnouncements < WANT_ANNOUNCEMENTS) {
				if(seeds.isEmpty()) {
					if(logMINOR)
						Logger.minor(this, "No more seednodes, announcedTo = "+announcedToIdentities.size());
					break;
				}
				final SeedServerPeerNode seed = ListUtils.removeRandomBySwapLastSimple(node.random, seeds);
				InetAddress[] addrs = seed.getInetAddresses();
				if(!newAnnouncedIPs(addrs)) {
					if(logMINOR)
						Logger.minor(this, "Not announcing to "+seed+" because already used those IPs");
					continue;
				}
				addAnnouncedIPs(addrs);
				// If it throws, we do not want to increment, so call it first.
				if(sendAnnouncement(seed)) {
					sentAnnouncements++;
					runningAnnouncements++;
					announcedToIdentities.add(new ByteArrayWrapper(seed.peerECDSAPubKeyHash));
				}
			}
			if(runningAnnouncements >= WANT_ANNOUNCEMENTS) {
				if(logMINOR)
					Logger.minor(this, "Running "+runningAnnouncements+" announcements");
				return;
			}
			// Do we want to connect some more seednodes?
			if(now - timeAddedSeeds < MIN_ADDED_SEEDS_INTERVAL) {
				// Don't connect seednodes yet
				Logger.minor(this, "Waiting for MIN_ADDED_SEEDS_INTERVAL");
				node.getTicker().queueTimedJob(new Runnable() {
					@Override
					public void run() {
						try {
							maybeSendAnnouncement();
						} catch (Throwable t) {
							Logger.error(this, "Caught "+t+" trying to send announcements", t);
						}
					}
				}, (timeAddedSeeds + MIN_ADDED_SEEDS_INTERVAL) - now);
				return;
			}
		}
		connectSomeSeednodes();
	}

	private synchronized void addAnnouncedIPs(InetAddress[] addrs) {
		for (InetAddress addr : addrs)
	        announcedToIPs.add(addr);
	}

	/**
	 * Have we already announced to this node?
	 * Return true if the node has new non-local addresses we haven't announced to.
	 * Return false if the node has non-local addresses we have announced to.
	 * Return true if the node has no non-local addresses.
	 * @param addrs
	 * @return
	 */
	private synchronized boolean newAnnouncedIPs(InetAddress[] addrs) {
		boolean hasNonLocalAddresses = false;
		for(InetAddress addr: addrs) {
			if(!IPUtil.isValidAddress(addr, false))
				continue;
			hasNonLocalAddresses = true;
			if(!announcedToIPs.contains(addr))
				return true;
		}
		return !hasNonLocalAddresses;
	}

	protected boolean sendAnnouncement(final SeedServerPeerNode seed) {
		if(!node.isOpennetEnabled()) {
			if(logMINOR)
				Logger.minor(this, "Not announcing to "+seed+" because opennet is disabled");
			return false;
		}
		System.out.println("Announcement to "+seed.userToString()+" starting...");
		if(logMINOR)
			Logger.minor(this, "Announcement to "+seed.userToString()+" starting...");
		AnnounceSender sender = new AnnounceSender(node.getLocation(), om, node, new AnnouncementCallback() {
			private int totalAdded;
			private int totalNotWanted;
			private boolean acceptedSomewhere;
			@Override
			public synchronized void acceptedSomewhere() {
				acceptedSomewhere = true;
			}
			@Override
			public void addedNode(PeerNode pn) {
				synchronized(Announcer.this) {
					announcementAddedNodes++;
					totalAdded++;
				}
				Logger.normal(this, "Announcement to "+seed.userToString()+" added node "+pn+" for a total of "+announcementAddedNodes+" ("+totalAdded+" from this announcement)");
				System.out.println("Announcement to "+seed.userToString()+" added node "+pn.userToString()+'.');
				return;
			}
			@Override
			public void bogusNoderef(String reason) {
				Logger.normal(this, "Announcement to "+seed.userToString()+" got bogus noderef: "+reason, new Exception("debug"));
			}
			@Override
			public void completed() {
				boolean announceNow = false;
				synchronized(Announcer.this) {
					runningAnnouncements--;
					Logger.normal(this, "Announcement to "+seed.userToString()+" completed, now running "+runningAnnouncements+" announcements");
					if(runningAnnouncements == 0 && announcementAddedNodes > 0) {
						// No point waiting if no nodes have been added!
						startTime = System.currentTimeMillis() + COOLING_OFF_PERIOD;
						sentAnnouncements = 0;
						// Wait for COOLING_OFF_PERIOD before trying again
						node.getTicker().queueTimedJob(new Runnable() {

							@Override
							public void run() {
								maybeSendAnnouncement();
							}

						}, COOLING_OFF_PERIOD);
					} else if(runningAnnouncements == 0) {
						sentAnnouncements = 0;
						announceNow = true;
					}
				}
				// If it takes more than COOLING_OFF_PERIOD to disconnect, we might not be able to reannounce to this
				// node. However, we can't reannounce to it anyway until announcedTo is cleared, which probably will
				// be more than that period in the future.
				node.peers.disconnectAndRemove(seed, true, false, false);
				int shallow=node.maxHTL()-(totalAdded+totalNotWanted);
				if(acceptedSomewhere)
					System.out.println("Announcement to "+seed.userToString()+" completed ("+totalAdded+" added, "+totalNotWanted+" not wanted, "+shallow+" shallow)");
				else
					System.out.println("Announcement to "+seed.userToString()+" not accepted (version "+seed.getVersionNumber()+") .");
				if(announceNow)
					maybeSendAnnouncement();
			}

			@Override
			public void nodeFailed(PeerNode pn, String reason) {
				Logger.normal(this, "Announcement to node "+pn.userToString()+" failed: "+reason);
			}
			@Override
			public void noMoreNodes() {
				Logger.normal(this, "Announcement to "+seed.userToString()+" ran out of nodes (route not found)");
			}
			@Override
			public void nodeNotWanted() {
				synchronized(Announcer.this) {
					announcementNotWantedNodes++;
					totalNotWanted++;
				}
				Logger.normal(this, "Announcement to "+seed.userToString()+" returned node not wanted for a total of "+announcementNotWantedNodes+" ("+totalNotWanted+" from this announcement)");
			}
			@Override
			public void nodeNotAdded() {
				Logger.normal(this, "Announcement to "+seed.userToString()+" : node not wanted (maybe already have it, opennet just turned off, etc)");
			}
			@Override
			public void relayedNoderef() {
				Logger.error(this, "Announcement to "+seed.userToString()+" : RELAYED ?!?!?!");
			}
		}, seed);
		node.executor.execute(sender, "Announcer to "+seed);
		return true;
	}

	class AnnouncementUserEvent extends AbstractUserEvent {

		private final int status;

		public AnnouncementUserEvent(int status) {
			this.status = status;
		}

		@Override
		public String dismissButtonText() {
			return NodeL10n.getBase().getString("UserAlert.hide");
		}

		@Override
		public HTMLNode getHTMLText() {
			return new HTMLNode("#", getText());
		}

		@Override
		public short getPriorityClass() {
			return UserAlert.ERROR;
		}

		@Override
		public String getText() {
			StringBuilder sb = new StringBuilder();
			sb.append(l10n("announceAlertIntro"));
			if(status == STATUS_NO_SEEDNODES) {
				return l10n("announceAlertNoSeednodes");
			}
			if(status == STATUS_LOADING) {
				return l10n("announceLoading");
			}
			if(node.clientCore.isAdvancedModeEnabled()) {
				// Detail
				sb.append(' ');
				int addedNodes;
				int refusedNodes;
				int recentSentAnnouncements;
				int runningAnnouncements;
				int connectedSeednodes = 0;
				int disconnectedSeednodes = 0;
				long coolingOffSeconds = Math.max(0, startTime - System.currentTimeMillis()) / 1000;
				synchronized(this) {
					addedNodes = announcementAddedNodes;
					refusedNodes = announcementNotWantedNodes;
					recentSentAnnouncements = sentAnnouncements;
					runningAnnouncements = Announcer.this.runningAnnouncements;

				}
				List<SeedServerPeerNode> nodes = node.peers.getSeedServerPeersVector();
				for(SeedServerPeerNode seed : nodes) {
					if(seed.isConnected())
						connectedSeednodes++;
					else
						disconnectedSeednodes++;
				}
				sb.append(l10n("announceDetails",
						new String[] { "addedNodes", "refusedNodes", "recentSentAnnouncements", "runningAnnouncements", "connectedSeednodes", "disconnectedSeednodes" },
						new String[] {
						Integer.toString(addedNodes),
						Integer.toString(refusedNodes),
						Integer.toString(recentSentAnnouncements),
						Integer.toString(runningAnnouncements),
						Integer.toString(connectedSeednodes),
						Integer.toString(disconnectedSeednodes)
				}));
				if(coolingOffSeconds > 0) {
					sb.append(' ');
					sb.append(l10n("coolingOff", "time", Long.toString(coolingOffSeconds)));
				}
			}
			return sb.toString();
		}

		@Override
		public String getTitle() {
			return l10n("announceAlertTitle");
		}

		@Override
		public boolean isValid() {
			return (!enoughPeers()) && node.isOpennetEnabled();
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
			return true;
		}

		@Override
		public boolean userCanDismiss() {
			return true;
		}

		@Override
		public String anchor() {
			return "announcer:"+hashCode();
		}

		@Override
		public String getShortText() {
			return l10n("announceAlertShort");
		}

		@Override
		public boolean isEventNotification() {
			return false;
		}

		@Override
		public UserEvent.Type getEventType() {
			return UserEvent.Type.Announcer;
		}

	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("Announcer."+key);
	}

	protected String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("Announcer."+key, patterns, values);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("Announcer."+key, pattern, value);
	}

	public void reannounce() {
		System.out.println("Re-announcing...");
		maybeSendAnnouncementOffThread();
	}

	public boolean isWaitingForUpdater() {
		synchronized(this) {
			return killedAnnouncementTooOld;
		}
	}
}
