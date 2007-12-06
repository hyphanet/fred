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
import java.util.HashSet;
import java.util.Vector;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Closer;
import freenet.support.transport.ip.IPUtil;

/**
 * Decide whether to announce, and announce if necessary to a node in the
 * routing table, or to a seednode.
 * @author toad
 */
public class Announcer {

	final Node node;
	final OpennetManager om;
	private int status;
	final int STATUS_LOADING = 0;
	final int STATUS_CONNECTING_SEEDNODES = 1;
	final int STATUS_NO_SEEDNODES = -1;
	private int runningAnnouncements;
	/** We want to announce to 3 different seednodes. */
	final int WANT_ANNOUNCEMENTS = 3;
	private int sentAnnouncements;
	/** The time when we last sent an announcement. */
	private long timeSentAnnouncement;
	private long timeCompletedAnnouncement;
	private long startTime;
	private long timeAddedSeeds;
	static final long MIN_ADDED_SEEDS_INTERVAL = 60*1000;
	/** After we have sent 3 announcements, wait for 1 minute before sending 3 more if we still have no connections. */
	static final int COOLING_OFF_PERIOD = 60*1000;
	/** Identities of nodes we have announced to */
	private final HashSet announcedToIdentities;
	/** IPs of nodes we have announced to. Maybe this should be first-two-bytes, but I'm not sure how to do that with IPv6. */
	private final HashSet announcedToIPs;
	/** How many nodes to connect to at once? */
	static final int CONNECT_AT_ONCE = 10;
	/** Do not announce if there are more than this many opennet peers connected */
	private static final int MIN_OPENNET_CONNECTED_PEERS = 10;
	/** Identities of nodes we have tried to connect to */
	private final HashSet connectedToIdentities;
	/** Total nodes added by announcement so far */
	private int announcementAddedNodes;
	/** Total nodes that didn't want us so far */
	private int announcementNotWantedNodes;
	
	Announcer(OpennetManager om) {
		this.om = om;
		this.node = om.node;
		announcedToIdentities = new HashSet();
		announcedToIPs = new HashSet();
		connectedToIdentities = new HashSet();
	}

	public void start() {
		if(node.peers.getDarknetPeers().length + node.peers.getOpennetPeers().length + om.countOldOpennetPeers() == 0) {
			// We know opennet is enabled.
			// We have no peers AT ALL.
			// So lets connect to a few seednodes, and attempt an announcement.
			System.err.println("Attempting announcement to seednodes...");
			synchronized(this) {
				status = STATUS_LOADING;
			}
			registerAlert();
			connectSomeSeednodes();
		} else {
			// Wait a minute, then check whether we need to seed.
			node.getTicker().queueTimedJob(new Runnable() {
				public void run() {
					try {
						maybeSendAnnouncement();
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t+" trying to send announcements", t);
					}
				}
			}, MIN_ADDED_SEEDS_INTERVAL);
		}
	}
	
	private void registerAlert() {
		// TODO Auto-generated method stub
		
	}

	private void connectSomeSeednodes() {
		Vector/*<SimpleFieldSet>*/ seeds = readSeednodes();
		if(seeds.size() == 0) {
			synchronized(this) {
				status = STATUS_NO_SEEDNODES;
				return;
			}
		} else {
			status = STATUS_CONNECTING_SEEDNODES;
		}
		// Try to connect to some seednodes.
		// Once they are connected they will report back and we can attempt an announcement.

		int count = 0;
		while(count < CONNECT_AT_ONCE) {
			if(seeds.size() == 0) break;
			SimpleFieldSet fs = (SimpleFieldSet) seeds.remove(node.random.nextInt(seeds.size()));
			try {
				SeedServerPeerNode seed =
					new SeedServerPeerNode(fs, node, om.crypto, node.peers, false, om.crypto.packetMangler);
				if(node.peers.addPeer(seed)) {
					count++;
					connectedToIdentities.add(seed.identity);
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
			}
			
		}
		// If none connect in a minute, try some more.
		node.getTicker().queueTimedJob(new Runnable() {
			public void run() {
				try {
					maybeSendAnnouncement();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" trying to send announcements", t);
				}
			}
		}, MIN_ADDED_SEEDS_INTERVAL);
	}

	private Vector readSeednodes() {
		File file = new File(node.nodeDir, "seednodes.fref");
		Vector list = new Vector();
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			BufferedInputStream bis = new BufferedInputStream(fis);
			InputStreamReader isr = new InputStreamReader(bis, "UTF-8");
			BufferedReader br = new BufferedReader(isr);
			while(true) {
				try {
					SimpleFieldSet fs = new SimpleFieldSet(br, false, false);
					list.add(fs);
				} catch (EOFException e) {
					return list;
				}
			}
		} catch (IOException e) {
			return list;
		} finally {
			Closer.close(fis);
		}
	}

	public void stop() {
		// Do nothing at present
	}

	public void maybeSendAnnouncement() {
		// Do we want to send an announcement to the node?
		int opennetCount = node.peers.countConnectedOpennetPeers();
		// First, do we actually need to announce?
		if(opennetCount > MIN_OPENNET_CONNECTED_PEERS) {
			return;
		}
		long now = System.currentTimeMillis();
		synchronized(this) {
			// Second, do we have many announcements running?
			if(runningAnnouncements > WANT_ANNOUNCEMENTS) {
				return;
			}
			// In cooling-off period?
			if(System.currentTimeMillis() < startTime) {
				return;
			}
			if(sentAnnouncements >= WANT_ANNOUNCEMENTS) {
				return;
			}
			// Now find a node to announce to
			Vector seeds = node.peers.getSeedServerPeersVector(announcedToIdentities);
			while(sentAnnouncements < WANT_ANNOUNCEMENTS) {
				if(seeds.isEmpty()) break;
				final SeedServerPeerNode seed = (SeedServerPeerNode) seeds.remove(node.random.nextInt(seeds.size()));
				InetAddress[] addrs = seed.getInetAddresses();
				if(!newAnnouncedIPs(addrs)) continue;
				addAnnouncedIPs(addrs);
				sentAnnouncements++;
				runningAnnouncements++;
				timeSentAnnouncement = now;
				announcedToIdentities.add(seed.getIdentity());
				sendAnnouncement(seed);
			}
			if(runningAnnouncements >= WANT_ANNOUNCEMENTS)
				return;
			// Do we want to connect some more seednodes?
			if(now - timeAddedSeeds < MIN_ADDED_SEEDS_INTERVAL) {
				// Don't connect seednodes yet
				node.getTicker().queueTimedJob(new Runnable() {
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
		for(int i=0;i<addrs.length;i++)
			announcedToIPs.add(addrs[i]);
	}

	private synchronized boolean newAnnouncedIPs(InetAddress[] addrs) {
		for(int i=0;i<addrs.length;i++) {
			if(!IPUtil.isValidAddress(addrs[i], false))
				continue;
			if(!announcedToIPs.contains(addrs[i]))
				return true;
		}
		return false;
	}

	public void sendAnnouncement(final SeedServerPeerNode seed) {
		AnnounceSender sender = new AnnounceSender(node.getLocation(), om, node, new AnnouncementCallback() {
			private int totalAdded;
			private int totalNotWanted;
			public void addedNode(PeerNode pn) {
				synchronized(Announcer.this) {
					announcementAddedNodes++;
					totalAdded++;
				}
				Logger.error(this, "Announcement to "+seed+" added node "+pn+" for a total of "+announcementAddedNodes+" ("+totalAdded+" from this announcement)");
				return;
			}
			public void bogusNoderef(String reason) {
				Logger.error(this, "Announcement to "+seed+" got bogus noderef: "+reason, new Exception("debug"));
			}
			public void completed() {
				long now = System.currentTimeMillis();
				synchronized(Announcer.this) {
					runningAnnouncements--;
					Logger.error(this, "Announcement to "+seed+" completed, now running "+runningAnnouncements+" announcements");
					timeCompletedAnnouncement = now;
					if(runningAnnouncements == 0) {
						startTime = System.currentTimeMillis() + COOLING_OFF_PERIOD;
						sentAnnouncements = 0;
						// Wait for COOLING_OFF_PERIOD before trying again
						node.getTicker().queueTimedJob(new Runnable() {

							public void run() {
								maybeSendAnnouncement();
							}
							
						}, COOLING_OFF_PERIOD);
					}
				}
			}

			public void nodeFailed(PeerNode pn, String reason) {
				Logger.error(this, "Announcement to node "+pn+" failed");
			}
			public void nodeNotWanted() {
				synchronized(Announcer.this) {
					announcementNotWantedNodes++;
					totalNotWanted++;
				}
				Logger.error(this, "Announcement to "+seed+" returned node not wanted for a total of "+announcementNotWantedNodes+" ("+totalNotWanted+" from this announcement)");
			}
		}, seed);
		node.executor.execute(sender, "Announcer to "+seed);
	}

}
