/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;
import java.util.ArrayList;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.Key;
import freenet.node.useralerts.PeerManagerUserAlert;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.SimpleFieldSet;
import freenet.support.io.FileUtil;
import freenet.support.io.Closer;

/**
 * @author amphibian
 * 
 * Maintains:
 * - A list of peers we want to connect to.
 * - A list of peers we are actually connected to.
 * - Each peer's Location.
 */
public class PeerManager {

	private static boolean logMINOR;
	/** Our Node */
	final Node node;
	/** All the peers we want to connect to */
	PeerNode[] myPeers;
	/** All the peers we are actually connected to */
	PeerNode[] connectedPeers;
	private String darkFilename;
	private String openFilename;
	private PeerManagerUserAlert ua;	// Peers stuff
	/** age of oldest never connected peer (milliseconds) */
	private long oldestNeverConnectedPeerAge;
	/** Next time to update oldestNeverConnectedPeerAge */
	private long nextOldestNeverConnectedPeerAgeUpdateTime = -1;
	/** oldestNeverConnectedPeerAge update interval (milliseconds) */
	private static final long oldestNeverConnectedPeerAgeUpdateInterval = 5000;
	/** Next time to log the PeerNode status summary */
	private long nextPeerNodeStatusLogTime = -1;
	/** PeerNode status summary log interval (milliseconds) */
	private static final long peerNodeStatusLogInterval = 5000;
	/** PeerNode statuses, by status */
	private final HashMap peerNodeStatuses;
	/** DarknetPeerNode statuses, by status */
	private final HashMap peerNodeStatusesDarknet;
	/** PeerNode routing backoff reasons, by reason */
	private final HashMap peerNodeRoutingBackoffReasons;
	/** Next time to update routableConnectionStats */
	private long nextRoutableConnectionStatsUpdateTime = -1;
	/** routableConnectionStats update interval (milliseconds) */
	private static final long routableConnectionStatsUpdateInterval = 7 * 1000;  // 7 seconds
	public static final int PEER_NODE_STATUS_CONNECTED = 1;
	public static final int PEER_NODE_STATUS_ROUTING_BACKED_OFF = 2;
	public static final int PEER_NODE_STATUS_TOO_NEW = 3;
	public static final int PEER_NODE_STATUS_TOO_OLD = 4;
	public static final int PEER_NODE_STATUS_DISCONNECTED = 5;
	public static final int PEER_NODE_STATUS_NEVER_CONNECTED = 6;
	public static final int PEER_NODE_STATUS_DISABLED = 7;
	public static final int PEER_NODE_STATUS_BURSTING = 8;
	public static final int PEER_NODE_STATUS_LISTENING = 9;
	public static final int PEER_NODE_STATUS_LISTEN_ONLY = 10;
	public static final int PEER_NODE_STATUS_CLOCK_PROBLEM = 11;
	public static final int PEER_NODE_STATUS_CONN_ERROR = 12;
	public static final int PEER_NODE_STATUS_DISCONNECTING = 13;
	public static final int PEER_NODE_STATUS_ROUTING_DISABLED = 14;

	/**
	 * Create a PeerManager by reading a list of peers from
	 * a file.
	 * @param node
	 * @param filename
	 */
	public PeerManager(Node node) {
		Logger.normal(this, "Creating PeerManager");
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		peerNodeStatuses = new HashMap();
		peerNodeStatusesDarknet = new HashMap();
		peerNodeRoutingBackoffReasons = new HashMap();
		System.out.println("Creating PeerManager");
		myPeers = new PeerNode[0];
		connectedPeers = new PeerNode[0];
		this.node = node;
	}

	/**
	 * Attempt to read a file full of noderefs. Try the file as named first, then the .bak if it is empty or
	 * otherwise doesn't work.
	 * @param filename The filename to read from. If this doesn't work, we try the .bak file.
	 * @param crypto The cryptographic identity which these nodes are connected to.
	 * @param opennet The opennet manager for the nodes. Only needed (for constructing the nodes) if isOpennet.
	 * @param isOpennet Whether the file contains opennet peers.
	 * @param oldOpennetPeers If true, don't add the nodes to the routing table, pass them to the opennet
	 * manager as "old peers" i.e. inactive nodes which may try to reconnect.
	 */
	void tryReadPeers(String filename, NodeCrypto crypto, OpennetManager opennet, boolean isOpennet, boolean oldOpennetPeers) {
		synchronized(writePeersSync) {
			if(!oldOpennetPeers)
				if(isOpennet)
					openFilename = filename;
				else
					darkFilename = filename;
		}
		OutgoingPacketMangler mangler = crypto.packetMangler;
		File peersFile = new File(filename);
		File backupFile = new File(filename + ".bak");
		// Try to read the node list from disk
		if(peersFile.exists())
			if(readPeers(peersFile, mangler, crypto, opennet, oldOpennetPeers)) {
				String msg;
				if(oldOpennetPeers)
					msg = "Read " + opennet.countOldOpennetPeers() + " old-opennet-peers from " + peersFile;
				else if(isOpennet)
					msg = "Read " + getOpennetPeers().length + " opennet peers from " + peersFile;
				else
					msg = "Read " + getDarknetPeers().length + " darknet peers from " + peersFile;
				Logger.normal(this, msg);
				System.out.println(msg);
				return;
			}
		// Try the backup
		if(backupFile.exists())
			if(readPeers(backupFile, mangler, crypto, opennet, oldOpennetPeers)) {
				String msg;
				if(oldOpennetPeers)
					msg = "Read " + opennet.countOldOpennetPeers() + " old-opennet-peers from " + peersFile;
				else if(isOpennet)
					msg = "Read " + getOpennetPeers().length + " opennet peers from " + peersFile;
				else
					msg = "Read " + getDarknetPeers().length + " darknet peers from " + peersFile;
				Logger.normal(this, msg);
				System.out.println(msg);
			} else {
				Logger.error(this, "No (readable) peers file with peers in it found");
				System.err.println("No (readable) peers file with peers in it found");
			}
	}

	private boolean readPeers(File peersFile, OutgoingPacketMangler mangler, NodeCrypto crypto, OpennetManager opennet, boolean oldOpennetPeers) {
		boolean gotSome = false;
		FileInputStream fis;
		try {
			fis = new FileInputStream(peersFile);
		} catch(FileNotFoundException e4) {
			Logger.normal(this, "Peers file not found: " + peersFile);
			return false;
		}
		InputStreamReader ris;
		try {
			ris = new InputStreamReader(fis, "UTF-8");
		} catch(UnsupportedEncodingException e4) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e4, e4);
		}
		BufferedReader br = new BufferedReader(ris);
		try { // FIXME: no better way?
			while(true) {
				// Read a single NodePeer
				SimpleFieldSet fs;
				fs = new SimpleFieldSet(br, false, true);
				PeerNode pn;
				try {
					pn = PeerNode.create(fs, node, crypto, opennet, this, true, mangler);
				} catch(FSParseException e2) {
					Logger.error(this, "Could not parse peer: " + e2 + '\n' + fs.toString(), e2);
					continue;
				} catch(PeerParseException e2) {
					Logger.error(this, "Could not parse peer: " + e2 + '\n' + fs.toString(), e2);
					continue;
				} catch(ReferenceSignatureVerificationException e2) {
					Logger.error(this, "Could not parse peer: " + e2 + '\n' + fs.toString(), e2);
					continue;
				}
				if(oldOpennetPeers)
					opennet.addOldOpennetNode(pn);
				else
					addPeer(pn, true, false);
				gotSome = true;
			}
		} catch(EOFException e) {
			// End of file, fine
		} catch(IOException e1) {
			Logger.error(this, "Could not read peers file: " + e1, e1);
		}
		try {
			br.close();
		} catch(IOException e3) {
			Logger.error(this, "Ignoring " + e3 + " caught reading " + peersFile, e3);
		}
		return gotSome;
	}

	public boolean addPeer(PeerNode pn) {
		return addPeer(pn, false, false);
	}

	/**
	 * Add a peer.
	 * @param pn The node to add to the routing table.
	 * @param ignoreOpennet If true, don't check for opennet peers. If false, check for opennet peers and if so,
	 * if opennet is enabled auto-add them to the opennet LRU, otherwise fail.
	 * @param reactivate If true, re-enable the peer if it is in state DISCONNECTING before re-adding it.
	 * @return True if the node was successfully added. False if it was already present, or if we tried to add
	 * an opennet peer when opennet was disabled.
	 */
	boolean addPeer(PeerNode pn, boolean ignoreOpennet, boolean reactivate) {
		assert (pn != null);
		if(reactivate)
			pn.forceCancelDisconnecting();
		synchronized(this) {
			for(int i = 0; i < myPeers.length; i++) {
				if(myPeers[i].equals(pn)) {
					if(logMINOR)
						Logger.minor(this, "Can't add peer " + pn + " because already have " + myPeers[i], new Exception("debug"));
					return false;
				}
			}
			PeerNode[] newMyPeers = new PeerNode[myPeers.length + 1];
			System.arraycopy(myPeers, 0, newMyPeers, 0, myPeers.length);
			newMyPeers[myPeers.length] = pn;
			myPeers = newMyPeers;
			Logger.normal(this, "Added " + pn);
		}
		if(pn.recordStatus())
			addPeerNodeStatus(pn.getPeerNodeStatus(), pn, false);
		pn.setPeerNodeStatus(System.currentTimeMillis());
		updatePMUserAlert();
		if((!ignoreOpennet) && pn instanceof OpennetPeerNode) {
			OpennetManager opennet = node.getOpennet();
			if(opennet != null)
				opennet.forceAddPeer(pn, true);
			else {
				Logger.error(this, "Adding opennet peer when no opennet enabled!!!: " + pn + " - removing...");
				removePeer(pn);
				return false;
			}
		}

		return true;
	}

	synchronized boolean havePeer(PeerNode pn) {
		for(int i = 0; i < myPeers.length; i++) {
			if(myPeers[i] == pn)
				return true;
		}
		return false;
	}

	private boolean removePeer(PeerNode pn) {
		if(logMINOR)
			Logger.minor(this, "Removing " + pn);
		boolean isInPeers = false;
		synchronized(this) {
			for(int i = 0; i < myPeers.length; i++) {
				if(myPeers[i] == pn)
					isInPeers = true;
			}
			if(pn instanceof DarknetPeerNode)
				((DarknetPeerNode) pn).removeExtraPeerDataDir();
			if(isInPeers) {

				int peerNodeStatus = pn.getPeerNodeStatus();
				if(pn.recordStatus())
					removePeerNodeStatus(peerNodeStatus, pn, !isInPeers);
				String peerNodePreviousRoutingBackoffReason = pn.getPreviousBackoffReason();
				if(peerNodePreviousRoutingBackoffReason != null)
					removePeerNodeRoutingBackoffReason(peerNodePreviousRoutingBackoffReason, pn);

				// removing from connectedPeers
				ArrayList a = new ArrayList();
				for(int i = 0; i < myPeers.length; i++) {
					if((myPeers[i] != pn) && myPeers[i].isConnected() && myPeers[i].isRealConnection())
						a.add(myPeers[i]);
				}

				PeerNode[] newConnectedPeers = new PeerNode[a.size()];
				newConnectedPeers = (PeerNode[]) a.toArray(newConnectedPeers);
				connectedPeers = newConnectedPeers;

				// removing from myPeers
				PeerNode[] newMyPeers = new PeerNode[myPeers.length - 1];
				int positionInNewArray = 0;
				for(int i = 0; i < myPeers.length; i++) {
					if(myPeers[i] != pn) {
						newMyPeers[positionInNewArray] = myPeers[i];
						positionInNewArray++;
					}
				}
				myPeers = newMyPeers;

				Logger.normal(this, "Removed " + pn);
			}
		}
		pn.onRemove();
		if(isInPeers)
			updatePMUserAlert();
		return true;
	}

	public boolean removeAllPeers() {
		Logger.normal(this, "removeAllPeers!");
		PeerNode[] oldPeers;
		synchronized(this) {
			oldPeers = myPeers;
			myPeers = new PeerNode[0];
			connectedPeers = new PeerNode[0];
		}
		for(int i = 0; i < oldPeers.length; i++)
			oldPeers[i].onRemove();
		return true;
	}

	public boolean disconnected(PeerNode pn) {
		synchronized(this) {
			boolean isInPeers = false;
			for(int i = 0; i < connectedPeers.length; i++) {
				if(connectedPeers[i] == pn)
					isInPeers = true;
			}
			if(!isInPeers)
				return false;
			// removing from connectedPeers
			ArrayList a = new ArrayList();
			for(int i = 0; i < myPeers.length; i++) {
				if((myPeers[i] != pn) && myPeers[i].isRoutable())
					a.add(myPeers[i]);
			}
			PeerNode[] newConnectedPeers = new PeerNode[a.size()];
			newConnectedPeers = (PeerNode[]) a.toArray(newConnectedPeers);
			connectedPeers = newConnectedPeers;
		}
		updatePMUserAlert();
		node.lm.announceLocChange();
		return true;
	}
	long timeFirstAnyConnections = 0;

	public long getTimeFirstAnyConnections() {
		return timeFirstAnyConnections;
	}

	public void addConnectedPeer(PeerNode pn) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(!pn.isRealConnection()) {
			if(logMINOR)
				Logger.minor(this, "Not a real connection: " + pn);
			return;
		}
		if(!pn.isConnected()) {
			if(logMINOR)
				Logger.minor(this, "Not connected: " + pn);
			return;
		}
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(timeFirstAnyConnections == 0)
				timeFirstAnyConnections = now;
			for(int i = 0; i < connectedPeers.length; i++) {
				if(connectedPeers[i] == pn) {
					if(logMINOR)
						Logger.minor(this, "Already connected: " + pn);
					return;
				}
			}
			boolean inMyPeers = false;
			for(int i = 0; i < myPeers.length; i++) {
				if(myPeers[i] == pn) {
					inMyPeers = true;
					break;
				}
			}
			if(!inMyPeers) {
				Logger.error(this, "Connecting to " + pn + " but not in peers!");
				addPeer(pn);
			}
			if(logMINOR)
				Logger.minor(this, "Connecting: " + pn);
			PeerNode[] newConnectedPeers = new PeerNode[connectedPeers.length + 1];
			System.arraycopy(connectedPeers, 0, newConnectedPeers, 0, connectedPeers.length);
			newConnectedPeers[connectedPeers.length] = pn;
			connectedPeers = newConnectedPeers;
			if(logMINOR)
				Logger.minor(this, "Connected peers: " + connectedPeers.length);
		}
		updatePMUserAlert();
		node.lm.announceLocChange();
	}
//    NodePeer route(double targetLocation, RoutingContext ctx) {
//        double minDist = 1.1;
//        NodePeer best = null;
//        for(int i=0;i<connectedPeers.length;i++) {
//            NodePeer p = connectedPeers[i];
//            if(ctx.alreadyRoutedTo(p)) continue;
//            double loc = p.getLocation().getValue();
//            double dist = Math.abs(loc - targetLocation);
//            if(dist < minDist) {
//                minDist = dist;
//                best = p;
//            }
//        }
//        return best;
//    }
//    
//    NodePeer route(Location target, RoutingContext ctx) {
//        return route(target.getValue(), ctx);
//    }
//
	/**
	 * Find the node with the given Peer address.
	 */
	public PeerNode getByPeer(Peer peer) {
		for(int i = 0; i < myPeers.length; i++) {
			if(!myPeers[i].isRealConnection())
				continue;
			if(peer.equals(myPeers[i].getPeer()))
				return myPeers[i];
		}
		return null;
	}

	/**
	 * Connect to a node provided the fieldset representing it.
	 */
	public void connect(SimpleFieldSet noderef, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		PeerNode pn = node.createNewDarknetNode(noderef);
		for(int i = 0; i < myPeers.length; i++) {
			if(Arrays.equals(myPeers[i].identity, pn.identity))
				return;
		}
		addPeer(pn);
	}

	/**
	 * Disconnect from a specified node
	 */
	public void disconnect(final PeerNode pn, boolean sendDisconnectMessage, final boolean waitForAck) {
		if(logMINOR)
			Logger.minor(this, "Disconnecting " + pn.shortToString());
		synchronized(this) {
			if(!havePeer(pn))
				return;
		}
		pn.notifyDisconnecting();
		if(sendDisconnectMessage) {
			Message msg = DMT.createFNPDisconnect(true, false, -1, new ShortBuffer(new byte[0]));
			try {
				pn.sendAsync(msg, new AsyncMessageCallback() {

					boolean done = false;

					public void acknowledged() {
						done();
					}

					public void disconnected() {
						done();
					}

					public void fatalError() {
						done();
					}

					public void sent() {
						if(!waitForAck)
							done();
					}

					void done() {
						synchronized(this) {
							if(done)
								return;
							done = true;
						}
						if(removePeer(pn))
							writePeers();
					}
				}, 0, ctrDisconn);
			} catch(NotConnectedException e) {
				if(pn.isDisconnecting() && removePeer(pn))
					writePeers();
				return;
			}
			node.getTicker().queueTimedJob(new Runnable() {

				public void run() {
					if(pn.isDisconnecting() && removePeer(pn))
						writePeers();
				}
			}, Node.MAX_PEER_INACTIVITY);
		} else
			if(removePeer(pn))
				writePeers();
	}
	final ByteCounter ctrDisconn = new ByteCounter() {

		public void receivedBytes(int x) {
			node.nodeStats.disconnBytesReceived(x);
		}

		public void sentBytes(int x) {
			node.nodeStats.disconnBytesSent(x);
		}

		public void sentPayload(int x) {
			// Ignore
		}
	};

	protected static class LocationUIDPair implements Comparable {

		double location;
		long uid;

		LocationUIDPair(PeerNode pn) {
			location = pn.getLocation();
			uid = pn.swapIdentifier;
		}

		public int compareTo(Object arg0) {
			// Compare purely on location, so result is the same as getPeerLocationDoubles()
			LocationUIDPair p = (LocationUIDPair) arg0;
			if(p.location > location)
				return 1;
			if(p.location < location)
				return -1;
			return 0;
		}
	}

	/**
	 * @return An array of the current locations (as doubles) of all
	 * our connected peers or double[0] if Node.shallWePublishOurPeersLocation() is false
	 */
	public double[] getPeerLocationDoubles(boolean pruneBackedOffedPeers) {
		double[] locs;
		if(!node.shallWePublishOurPeersLocation())
			return new double[0];
		PeerNode[] conns;
		synchronized(this) {
			conns = connectedPeers;
		}
		locs = new double[conns.length];
		int x = 0;
		for(int i = 0; i < conns.length; i++) {
			if(conns[i].isRoutable()) {
				if(!conns[i].shouldBeExcludedFromPeerList()) {
					locs[x++] = conns[i].getLocation();
				}
			}
		}
		// Wipe out any information contained in the order
		java.util.Arrays.sort(locs, 0, x);
		if(x != locs.length) {
			double[] newLocs = new double[x];
			System.arraycopy(locs, 0, newLocs, 0, x);
			return newLocs;
		} else
			return locs;
	}

	/** Just like getPeerLocationDoubles, except it also
	 * returns the UID for each node. */
	public LocationUIDPair[] getPeerLocationsAndUIDs() {
		PeerNode[] conns;
		LocationUIDPair[] locPairs;
		synchronized(this) {
			conns = myPeers;
		}
		locPairs = new LocationUIDPair[conns.length];
		int x = 0;
		for(int i = 0; i < conns.length; i++) {
			if(conns[i].isRoutable())
				locPairs[x++] = new LocationUIDPair(conns[i]);
		}
		// Sort it
		Arrays.sort(locPairs, 0, x);
		if(x != locPairs.length) {
			LocationUIDPair[] newLocs = new LocationUIDPair[x];
			System.arraycopy(locPairs, 0, newLocs, 0, x);
			return newLocs;
		} else
			return locPairs;
	}

	public PeerNode getRandomPeerInSwappingNetworkOf(PeerNode exclude) {
		if(exclude == null || exclude.networkGroup == null || NetworkIDManager.disableSwapSegregation)
			return getRandomPeer(exclude);
		synchronized(this) {
			if(connectedPeers.length == 0)
				return null;
			for(int i = 0; i < 5; i++) {
				PeerNode pn = connectedPeers[node.random.nextInt(connectedPeers.length)];
				if(pn == exclude)
					continue;
				if(node.netid.inSeparateNetworks(pn, exclude))
					continue;
				if(pn.isRoutable())
					return pn;
			}
			//could not easily find a good random one... filter the ones which are acceptable
			ArrayList l = new ArrayList();
			for(int i = 0; i < connectedPeers.length; i++) {
				PeerNode pn = connectedPeers[i];
				if(pn == exclude)
					continue;
				if(node.netid.inSeparateNetworks(pn, exclude))
					continue;
				if(!pn.isRoutable())
					continue;
				l.add(pn);
			}
			//Are there any acceptable peers?
			if(l.size() == 0)
				return null;
			return (PeerNode) l.get(node.random.nextInt(l.size()));
		}
	}

	/**
	 * @return A random routable connected peer.
	 * FIXME: should this take performance into account?
	 * DO NOT remove the "synchronized". See below for why.
	 */
	public synchronized PeerNode getRandomPeer(PeerNode exclude) {
		if(connectedPeers.length == 0)
			return null;
		for(int i = 0; i < 5; i++) {
			PeerNode pn = connectedPeers[node.random.nextInt(connectedPeers.length)];
			if(pn == exclude)
				continue;
			if(pn.isRoutable())
				return pn;
		}
		// None of them worked
		// Move the un-connected ones out
		// This is safe as they will add themselves when they
		// reconnect, and they can't do it yet as we are synchronized.
		Vector v = new Vector(connectedPeers.length);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		for(int i = 0; i < myPeers.length; i++) {
			PeerNode pn = myPeers[i];
			if(pn == exclude)
				continue;
			if(pn.isRoutable())
				v.add(pn);
			else
				if(logMINOR)
					Logger.minor(this, "Excluding " + pn + " because is disconnected");
		}
		int lengthWithoutExcluded = v.size();
		if((exclude != null) && exclude.isRoutable())
			v.add(exclude);
		PeerNode[] newConnectedPeers = new PeerNode[v.size()];
		newConnectedPeers = (PeerNode[]) v.toArray(newConnectedPeers);
		if(logMINOR)
			Logger.minor(this, "Connected peers (in getRandomPeer): " + newConnectedPeers.length + " was " + connectedPeers.length);
		connectedPeers = newConnectedPeers;
		if(lengthWithoutExcluded == 0)
			return null;
		return connectedPeers[node.random.nextInt(lengthWithoutExcluded)];
	}

	/**
	 * Asynchronously send this message to every connected peer.
	 */
	public void localBroadcast(Message msg, boolean ignoreRoutability, boolean onlyRealConnections, ByteCounter ctr) {
		PeerNode[] peers;
		synchronized(this) {
			// myPeers not connectedPeers as connectedPeers only contains
			// ROUTABLE peers, and we may want to send to non-routable peers
			peers = myPeers;
		}
		for(int i = 0; i < peers.length; i++) {
			if(ignoreRoutability) {
				if(!peers[i].isConnected())
					continue;
			} else
				if(!peers[i].isRoutable())
					continue;
			if(onlyRealConnections && !peers[i].isRealConnection())
				continue;
			try {
				peers[i].sendAsync(msg, null, 0, ctr);
			} catch(NotConnectedException e) {
				// Ignore
			}
		}
	}

	/**
	 * Asynchronously send a differential node reference to every isConnected() peer.
	 */
	public void locallyBroadcastDiffNodeRef(SimpleFieldSet fs, boolean toDarknetOnly, boolean toOpennetOnly) {
		PeerNode[] peers;
		synchronized(this) {
			// myPeers not connectedPeers as connectedPeers only contains
			// ROUTABLE peers and we want to also send to non-routable peers
			peers = myPeers;
		}
		for(int i = 0; i < peers.length; i++) {
			if(!peers[i].isConnected())
				continue;
			if(toDarknetOnly && !peers[i].isDarknet())
				continue;
			if(toOpennetOnly && !peers[i].isOpennet())
				continue;
			peers[i].sendNodeToNodeMessage(fs, Node.N2N_MESSAGE_TYPE_DIFFNODEREF, false, 0, false);
		}
	}

	public PeerNode getRandomPeer() {
		return getRandomPeer(null);
	}

	public double closestPeerLocation(double loc, double ignoreLoc, int minUptimePercent) {
		PeerNode[] peers;
		synchronized(this) {
			peers = connectedPeers;
		}
		double bestDiff = 1.0;
		double bestLoc = Double.MAX_VALUE;
		boolean foundOne = false;
		for(int i = 0; i < peers.length; i++) {
			PeerNode p = peers[i];
			if(!p.isRoutable())
				continue;
			if(p.isRoutingBackedOff())
				continue;
			if(p.getUptime() < minUptimePercent)
				continue;
			double peerloc = p.getLocation();
			if(Math.abs(peerloc - ignoreLoc) < Double.MIN_VALUE * 2)
				continue;
			double diff = Location.distance(peerloc, loc);
			if(diff < bestDiff) {
				foundOne = true;
				bestDiff = diff;
				bestLoc = peerloc;
			}
		}
		if(!foundOne)
			for(int i = 0; i < peers.length; i++) {
				PeerNode p = peers[i];
				if(!p.isRoutable())
					continue;
				if(p.getUptime() < minUptimePercent)
					continue;
				// Ignore backoff state
				double peerloc = p.getLocation();
				if(Math.abs(peerloc - ignoreLoc) < Double.MIN_VALUE * 2)
					continue;
				double diff = Location.distance(peerloc, loc);
				if(diff < bestDiff) {
					foundOne = true;
					bestDiff = diff;
					bestLoc = peerloc;
				}
			}
		return bestLoc;
	}

	public boolean isCloserLocation(double loc, int minUptimePercent) {
		double nodeLoc = node.lm.getLocation();
		double nodeDist = Location.distance(nodeLoc, loc);
		double closest = closestPeerLocation(loc, nodeLoc, minUptimePercent);
		if(closest > 1.0)
			// No peers found
			return false;
		double closestDist = Location.distance(closest, loc);
		return closestDist < nodeDist;
	}

	public PeerNode closerPeer(PeerNode pn, Set routedTo, Set notIgnored, double loc, boolean ignoreSelf, boolean calculateMisrouting, int minVersion, Vector addUnpickedLocsTo, Key key) {
		return closerPeer(pn, routedTo, notIgnored, loc, ignoreSelf, calculateMisrouting, minVersion, addUnpickedLocsTo, 2.0, key);
	}

	/**
	 * Find the peer, if any, which is closer to the target location than we are, and is not included in the provided set.
	 * If ignoreSelf==false, and we are closer to the target than any peers, this function returns null.
	 * This function returns two values, the closest such peer which is backed off, and the same which is not backed off.
	 * It is possible for either to be null independant of the other, 'closest' is the closer of the two in either case, and
	 * will not be null if any of the other two return values is not null.
	 * @param addUnpickedLocsTo Add all locations we didn't choose which we could have routed to to 
	 * this array. Remove the location of the peer we pick from it.
	 * @param maxDistance If a node is further away from the target than this distance, ignore it.
	 * @param key The original key, if we have it, and if we want to consult with the FailureTable
	 * to avoid routing to nodes which have recently failed for the same key.
	 */
	public PeerNode closerPeer(PeerNode pn, Set routedTo, Set notIgnored, double target, boolean ignoreSelf, boolean calculateMisrouting, int minVersion, Vector addUnpickedLocsTo, double maxDistance, Key key) {
		PeerNode[] peers;
		synchronized(this) {
			peers = connectedPeers;
		}
		if(!node.enablePerNodeFailureTables)
			key = null;
		if(logMINOR)
			Logger.minor(this, "Choosing closest peer: connectedPeers=" + peers.length);
		double maxDiff = Double.MAX_VALUE;
		if(!ignoreSelf)
			maxDiff = Location.distance(node.lm.getLocation(), target);

		/**
		 * Routing order:
		 * - Non-timed-out non-backed-off peers, in order of closeness to the target.
		 * - Timed-out, non-backed-off peers, least recently timed out first.
		 * - Non-timed-out backed-off peers, in order of closeness to the target.
		 * - Timed out, backed-off peers, least recently timed out first.
		 * - 
		 */
		PeerNode closest = null;
		double closestDistance = Double.MAX_VALUE;

		PeerNode closestBackedOff = null;
		double closestBackedOffDistance = Double.MAX_VALUE;

		PeerNode closestNotBackedOff = null;
		double closestNotBackedOffDistance = Double.MAX_VALUE;

		PeerNode leastRecentlyTimedOut = null;
		long timeLeastRecentlyTimedOut = Long.MAX_VALUE;

		PeerNode leastRecentlyTimedOutBackedOff = null;
		long timeLeastRecentlyTimedOutBackedOff = Long.MAX_VALUE;

		TimedOutNodesList entry = null;

		if(key != null)
			entry = node.failureTable.getTimedOutNodesList(key);

		long now = System.currentTimeMillis();
		int count = 0;
		for(int i = 0; i < peers.length; i++) {
			PeerNode p = peers[i];
			if(routedTo.contains(p)) {
				if(logMINOR)
					Logger.minor(this, "Skipping (already routed to): " + p.getPeer());
				continue;
			}
			if(p == pn) {
				if(logMINOR)
					Logger.minor(this, "Skipping (req came from): " + p.getPeer());
				continue;
			}
			if(!p.isRoutable()) {
				if(logMINOR)
					Logger.minor(this, "Skipping (not connected): " + p.getPeer());
				continue;
			}
			if(minVersion > 0 && Version.getArbitraryBuildNumber(p.getVersion(), -1) < minVersion) {
				if(logMINOR)
					Logger.minor(this, "Skipping old version: " + p.getPeer());
				continue;
			}
			long timeout = -1;
			if(entry != null)
				timeout = entry.getTimeoutTime(p);
			boolean timedOut = timeout > now;
			//To help avoid odd race conditions, get the location only once and use it for all calculations.
			double loc = p.getLocation();
			double diff = Location.distance(loc, target);
			
			double[] peersLocation = p.getPeersLocation();
			if((node.shallWeRouteAccordingToOurPeersLocation()) && (peersLocation != null)) {
				for(double l : peersLocation) {
					double newDiff = Location.distance(l, target);
					if(newDiff < diff) {
						loc = l;
						diff = newDiff;
					}
				}
				if(logMINOR)
					Logger.minor(this, "The peer "+p+" has published his peer's locations and the closest we have found to the target is "+diff+" away.");
			}
			
			if(diff > maxDistance)
				continue;
			if((!ignoreSelf) && (diff > maxDiff)) {
				if(logMINOR)
					Logger.minor(this, "Ignoring, further than self >maxDiff=" + maxDiff);
				continue;
			}
			count++;
			if(logMINOR)
				Logger.minor(this, "p.loc=" + loc + ", target=" + target + ", d=" + Location.distance(loc, target) + " usedD=" + diff + " timedOut=" + timedOut + " for " + p.getPeer());
			boolean chosen = false;
			if(diff < closestDistance) {
				closestDistance = diff;
				closest = p;
				chosen = true;
				if(logMINOR)
					Logger.minor(this, "New best: " + diff + " (" + loc + " for " + p.getPeer());
			}
			boolean backedOff = p.isRoutingBackedOff();
			if(backedOff && (diff < closestBackedOffDistance) && !timedOut) {
				closestBackedOffDistance = diff;
				closestBackedOff = p;
				chosen = true;
				if(logMINOR)
					Logger.minor(this, "New best-backed-off: " + diff + " (" + loc + " for " + p.getPeer());
			}
			if(!backedOff && (diff < closestNotBackedOffDistance) && !timedOut) {
				closestNotBackedOffDistance = diff;
				closestNotBackedOff = p;
				chosen = true;
				if(logMINOR)
					Logger.minor(this, "New best-not-backed-off: " + diff + " (" + loc + " for " + p.getPeer());
			}
			if(timedOut)
				if(!backedOff) {
					if(timeout < timeLeastRecentlyTimedOut) {
						timeLeastRecentlyTimedOut = timeout;
						leastRecentlyTimedOut = p;
					}
				} else
					if(timeout < timeLeastRecentlyTimedOutBackedOff) {
						timeLeastRecentlyTimedOutBackedOff = timeout;
						leastRecentlyTimedOutBackedOff = p;
					}
			if(addUnpickedLocsTo != null && !chosen) {
				Double d = new Double(loc);
				// Here we can directly compare double's because they aren't processed in any way, and are finite and (probably) nonzero.
				if(!addUnpickedLocsTo.contains(d))
					addUnpickedLocsTo.add(d);
			}
		}

		PeerNode best = closestNotBackedOff;

		if(best == null)
			if(leastRecentlyTimedOut != null) {
				// FIXME downgrade to DEBUG
				best = leastRecentlyTimedOut;
				if(logMINOR)
					Logger.minor(this, "Using least recently failed in-timeout-period peer for key: " + best.shortToString() + " for " + key);
			} else if(closestBackedOff != null) {
				best = closestBackedOff;
				if(logMINOR)
					Logger.minor(this, "Using best backed-off peer for key: " + best.shortToString());
			} else if(leastRecentlyTimedOutBackedOff != null) {
				best = leastRecentlyTimedOutBackedOff;
				if(logMINOR)
					Logger.minor(this, "Using least recently failed in-timeout-period backed-off peer for key: " + best.shortToString() + " for " + key);
			}

		//racy... getLocation() could have changed
		if(calculateMisrouting)
			if(best != null) {
				node.nodeStats.routingMissDistance.report(Location.distance(best, closest.getLocation()));
				int numberOfConnected = getPeerNodeStatusSize(PEER_NODE_STATUS_CONNECTED, false);
				int numberOfRoutingBackedOff = getPeerNodeStatusSize(PEER_NODE_STATUS_ROUTING_BACKED_OFF, false);
				if(numberOfRoutingBackedOff + numberOfConnected > 0)
					node.nodeStats.backedOffPercent.report((double) numberOfRoutingBackedOff / (double) (numberOfRoutingBackedOff + numberOfConnected));
			}

		//racy... getLocation() could have changed
		if(best != null && addUnpickedLocsTo != null)
			//Add the location which we did not pick, if it exists.
			if(closestNotBackedOff != null && closestBackedOff != null)
				addUnpickedLocsTo.add(new Double(closestBackedOff.getLocation()));

		return best;
	}

	/**
	 * @return Some status information
	 */
	public String getStatus() {
		StringBuffer sb = new StringBuffer();
		PeerNode[] peers;
		synchronized(this) {
			peers = myPeers;
		}
		String[] status = new String[peers.length];
		for(int i = 0; i < peers.length; i++) {
			PeerNode pn = peers[i];
			status[i] = pn.getStatus(true).toString();
			Version.seenVersion(pn.getVersion());
		}
		Arrays.sort(status);
		for(int i = 0; i < status.length; i++) {
			sb.append(status[i]);
			sb.append('\n');
		}
		return sb.toString();
	}

	/**
	 * @return TMCI peer list
	 */
	public String getTMCIPeerList() {
		StringBuffer sb = new StringBuffer();
		PeerNode[] peers;
		synchronized(this) {
			peers = myPeers;
		}
		String[] peerList = new String[peers.length];
		for(int i = 0; i < peers.length; i++) {
			PeerNode pn = peers[i];
			peerList[i] = pn.getTMCIPeerInfo();
		}
		Arrays.sort(peerList);
		for(int i = 0; i < peerList.length; i++) {
			sb.append(peerList[i]);
			sb.append('\n');
		}
		return sb.toString();
	}

	public String getFreevizOutput() {
		StringBuffer sb = new StringBuffer();
		PeerNode[] peers;
		synchronized(this) {
			peers = myPeers;
		}
		String[] identity = new String[peers.length];
		for(int i = 0; i < peers.length; i++) {
			PeerNode pn = peers[i];
			identity[i] = pn.getFreevizOutput();
		}
		Arrays.sort(identity);
		for(int i = 0; i < identity.length; i++) {
			sb.append(identity[i]);
			sb.append('\n');
		}
		return sb.toString();
	}
	private final Object writePeersSync = new Object();

	void writePeers() {
		node.ps.queueTimedJob(new Runnable() {

			public void run() {
				writePeersInner();
			}
		}, 0);
	}

	private void writePeersInner() {
		synchronized(writePeersSync) {
			if(darkFilename != null)
				writePeersInner(darkFilename, getDarknetPeers());
			OpennetManager om = node.getOpennet();
			if(om != null) {
				if(openFilename != null)
					writePeersInner(openFilename, getOpennetPeers());
				writePeersInner(om.getOldPeersFilename(), om.getOldPeers());
			}
		}
	}

	/**
	 * Write the peers file to disk
	 */
	private void writePeersInner(String filename, PeerNode[] peers) {
		synchronized(writePeersSync) {
			FileOutputStream fos = null;
			String f = filename + ".bak";
			try {
				fos = new FileOutputStream(f);
			} catch(FileNotFoundException e2) {
				Logger.error(this, "Cannot write peers to disk: Cannot create " + f + " - " + e2, e2);
				Closer.close(fos);
				return;
			}
			OutputStreamWriter w = null;
			try {
				w = new OutputStreamWriter(fos, "UTF-8");
			} catch(UnsupportedEncodingException e2) {
				Closer.close(w);
				throw new Error("Impossible: JVM doesn't support UTF-8: " + e2, e2);
			}
			BufferedWriter bw = new BufferedWriter(w);
			try {
				boolean succeeded = writePeers(bw, peers);
				bw.close();
				bw = null;
				if(!succeeded)
					return;

				File fnam = new File(filename);
				FileUtil.renameTo(new File(f), fnam);
			} catch(IOException e) {
				try {
					fos.close();
				} catch(IOException e1) {
					Logger.error(this, "Cannot close peers file: " + e, e);
				}
				Logger.error(this, "Cannot write file: " + e, e);
				return; // don't overwrite old file!
			} finally {
				Closer.close(bw);
				Closer.close(fos);
			}
		}
	}

	public boolean writePeers(Writer bw) {
		if(!writePeers(bw, getDarknetPeers()))
			return false;
		if(!writePeers(bw, getOpennetPeers()))
			return false;
		return true;
	}

	public boolean writePeers(Writer bw, PeerNode[] peers) {
		for(int i = 0; i < peers.length; i++) {
			try {
				peers[i].write(bw);
				bw.flush();
			} catch(IOException e) {
				try {
					bw.close();
				} catch(IOException e1) {
					Logger.error(this, "Cannot close file!: " + e1, e1);
				}
				Logger.error(this, "Cannot write peers to disk: " + e, e);
				return false;
			}
		}
		return true;
	}

	/**
	 * Update the numbers needed by our PeerManagerUserAlert on the UAM.
	 * Also run the node's onConnectedPeers() method if applicable
	 */
	public void updatePMUserAlert() {
		if(ua == null)
			return;
		int peers, darknetPeers, opennetPeers;
		synchronized(this) {
			darknetPeers = this.getDarknetPeers().length;
			opennetPeers = this.getOpennetPeers().length;
			peers = darknetPeers + opennetPeers; // Seednodes don't count.
		}
		OpennetManager om = node.getOpennet();

		boolean opennetDefinitelyPortForwarded;
		boolean opennetEnabled;
		boolean opennetAssumeNAT;
		if(om != null) {
			opennetEnabled = true;
			opennetDefinitelyPortForwarded = om.crypto.definitelyPortForwarded();
			opennetAssumeNAT = om.crypto.config.alwaysHandshakeAggressively();
		} else {
			opennetEnabled = false;
			opennetDefinitelyPortForwarded = false;
			opennetAssumeNAT = false;
		}
		boolean darknetDefinitelyPortForwarded = node.darknetDefinitelyPortForwarded();
		boolean darknetAssumeNAT = node.darknetCrypto.config.alwaysHandshakeAggressively();
		synchronized(ua) {
			ua.opennetDefinitelyPortForwarded = opennetDefinitelyPortForwarded;
			ua.darknetDefinitelyPortForwarded = darknetDefinitelyPortForwarded;
			ua.opennetAssumeNAT = opennetAssumeNAT;
			ua.darknetAssumeNAT = darknetAssumeNAT;
			ua.darknetConns = getPeerNodeStatusSize(PEER_NODE_STATUS_CONNECTED, true) +
				getPeerNodeStatusSize(PEER_NODE_STATUS_ROUTING_BACKED_OFF, true);
			ua.conns = getPeerNodeStatusSize(PEER_NODE_STATUS_CONNECTED, false) +
				getPeerNodeStatusSize(PEER_NODE_STATUS_ROUTING_BACKED_OFF, false);
			ua.darknetPeers = darknetPeers;
			ua.disconnDarknetPeers = darknetPeers - ua.darknetConns;
			ua.peers = peers;
			ua.neverConn = getPeerNodeStatusSize(PEER_NODE_STATUS_NEVER_CONNECTED, true);
			ua.clockProblem = getPeerNodeStatusSize(PEER_NODE_STATUS_CLOCK_PROBLEM, false);
			ua.connError = getPeerNodeStatusSize(PEER_NODE_STATUS_CONN_ERROR, true);
			ua.isOpennetEnabled = opennetEnabled;
		}
		if(anyConnectedPeers())
			node.onConnectedPeer();
	}

	public boolean anyConnectedPeers() {
		PeerNode[] conns;
		synchronized(this) {
			conns = connectedPeers;
		}
		for(int i = 0; i < conns.length; i++) {
			if(conns[i].isRoutable())
				return true;
		}
		return false;
	}

	/**
	 * Ask each PeerNode to read in it's extra peer data
	 */
	public void readExtraPeerData() {
		DarknetPeerNode[] peers = getDarknetPeers();
		for(int i = 0; i < peers.length; i++) {
			try {
				peers[i].readExtraPeerData();
			} catch(Exception e) {
				Logger.error(this, "Got exception while reading extra peer data", e);
			}
		}
		String msg = "Extra peer data reading and processing completed";
		Logger.normal(this, msg);
		System.out.println(msg);
	}

	public void start() {
		ua = new PeerManagerUserAlert(node.nodeStats);
		updatePMUserAlert();
		node.clientCore.alerts.register(ua);
	}

	public int countNonBackedOffPeers() {
		PeerNode[] peers;
		synchronized(this) {
			peers = connectedPeers; // even if myPeers peers are connected they won't be routed to
		}
		int countNoBackoff = 0;
		for(int i = 0; i < peers.length; i++) {
			if(peers[i].isRoutable())
				if(!peers[i].isRoutingBackedOff())
					countNoBackoff++;
		}
		return countNoBackoff;
	}
	// Stats stuff
	/**
	 * Update oldestNeverConnectedPeerAge if the timer has expired
	 */
	public void maybeUpdateOldestNeverConnectedPeerAge(long now) {
		synchronized(this) {
			if(now <= nextOldestNeverConnectedPeerAgeUpdateTime)
				return;
			nextOldestNeverConnectedPeerAgeUpdateTime = now + oldestNeverConnectedPeerAgeUpdateInterval;
		}
		oldestNeverConnectedPeerAge = 0;
		PeerNode[] peerList = myPeers;
		for(int i = 0; i < peerList.length; i++) {
			PeerNode pn = peerList[i];
			if(pn.getPeerNodeStatus() == PEER_NODE_STATUS_NEVER_CONNECTED)
				if((now - pn.getPeerAddedTime()) > oldestNeverConnectedPeerAge)
					oldestNeverConnectedPeerAge = now - pn.getPeerAddedTime();
		}
		if(oldestNeverConnectedPeerAge > 0 && logMINOR)
			Logger.minor(this, "Oldest never connected peer is " + oldestNeverConnectedPeerAge + "ms old");
		nextOldestNeverConnectedPeerAgeUpdateTime = now + oldestNeverConnectedPeerAgeUpdateInterval;
	}

	public long getOldestNeverConnectedPeerAge() {
		return oldestNeverConnectedPeerAge;
	}

	/**
	 * Log the current PeerNode status summary if the timer has expired
	 */
	public void maybeLogPeerNodeStatusSummary(long now) {
		if(now > nextPeerNodeStatusLogTime) {
			if((now - nextPeerNodeStatusLogTime) > (10 * 1000) && nextPeerNodeStatusLogTime > 0)
				Logger.error(this, "maybeLogPeerNodeStatusSummary() not called for more than 10 seconds (" + (now - nextPeerNodeStatusLogTime) + ").  PacketSender getting bogged down or something?");

			int numberOfConnected = 0;
			int numberOfRoutingBackedOff = 0;
			int numberOfTooNew = 0;
			int numberOfTooOld = 0;
			int numberOfDisconnected = 0;
			int numberOfNeverConnected = 0;
			int numberOfDisabled = 0;
			int numberOfListenOnly = 0;
			int numberOfListening = 0;
			int numberOfBursting = 0;
			int numberOfClockProblem = 0;
			int numberOfConnError = 0;
			int numberOfDisconnecting = 0;
			int numberOfRoutingDisabled = 0;

			PeerNodeStatus[] pns = getPeerNodeStatuses(true);

			for(int i = 0; i < pns.length; i++) {
				if(pns[i] == null) {
					Logger.error(this, "getPeerNodeStatuses(true)[" + i + "] == null!");
					continue;
				}
				switch(pns[i].getStatusValue()) {
					case PEER_NODE_STATUS_CONNECTED:
						numberOfConnected++;
						break;
					case PEER_NODE_STATUS_ROUTING_BACKED_OFF:
						numberOfRoutingBackedOff++;
						break;
					case PEER_NODE_STATUS_TOO_NEW:
						numberOfTooNew++;
						break;
					case PEER_NODE_STATUS_TOO_OLD:
						numberOfTooOld++;
						break;
					case PEER_NODE_STATUS_DISCONNECTED:
						numberOfDisconnected++;
						break;
					case PEER_NODE_STATUS_NEVER_CONNECTED:
						numberOfNeverConnected++;
						break;
					case PEER_NODE_STATUS_DISABLED:
						numberOfDisabled++;
						break;
					case PEER_NODE_STATUS_LISTEN_ONLY:
						numberOfListenOnly++;
						break;
					case PEER_NODE_STATUS_LISTENING:
						numberOfListening++;
						break;
					case PEER_NODE_STATUS_BURSTING:
						numberOfBursting++;
						break;
					case PEER_NODE_STATUS_CLOCK_PROBLEM:
						numberOfClockProblem++;
						break;
					case PEER_NODE_STATUS_CONN_ERROR:
						numberOfConnError++;
						break;
					case PEER_NODE_STATUS_DISCONNECTING:
						numberOfDisconnecting++;
						break;
					case PEER_NODE_STATUS_ROUTING_DISABLED:
						numberOfRoutingDisabled++;
						break;
					default:
						Logger.error(this, "Unknown peer status value : " + pns[i].getStatusValue());
						break;
				}
			}
			Logger.normal(this, "Connected: " + numberOfConnected + "  Routing Backed Off: " + numberOfRoutingBackedOff + "  Too New: " + numberOfTooNew + "  Too Old: " + numberOfTooOld + "  Disconnected: " + numberOfDisconnected + "  Never Connected: " + numberOfNeverConnected + "  Disabled: " + numberOfDisabled + "  Bursting: " + numberOfBursting + "  Listening: " + numberOfListening + "  Listen Only: " + numberOfListenOnly + "  Clock Problem: " + numberOfClockProblem + "  Connection Problem: " + numberOfConnError + "  Disconnecting: " + numberOfDisconnecting);
			nextPeerNodeStatusLogTime = now + peerNodeStatusLogInterval;
			node.displayClockProblemUserAlert(numberOfClockProblem > 2);
		}
	}

	/**
	 * Add a PeerNode status to the map
	 */
	public void addPeerNodeStatus(int pnStatus, PeerNode peerNode, boolean noLog) {
		Integer peerNodeStatus = new Integer(pnStatus);
		addPeerNodeStatuses(pnStatus, peerNode, peerNodeStatus, peerNodeStatuses, noLog);
		if(!peerNode.isOpennet())
			addPeerNodeStatuses(pnStatus, peerNode, peerNodeStatus, peerNodeStatusesDarknet, noLog);
	}

	private void addPeerNodeStatuses(int pnStatus, PeerNode peerNode, Integer peerNodeStatus, HashMap statuses, boolean noLog) {
		HashSet statusSet = null;
		synchronized(statuses) {
			if(statuses.containsKey(peerNodeStatus)) {
				statusSet = (HashSet) statuses.get(peerNodeStatus);
				if(statusSet.contains(peerNode)) {
					if(!noLog)
						Logger.error(this, "addPeerNodeStatus(): node already in peerNodeStatuses: " + peerNode + " status " + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()));
					return;
				}
				statuses.remove(peerNodeStatus);
			} else
				statusSet = new HashSet();
			if(logMINOR)
				Logger.minor(this, "addPeerNodeStatus(): adding PeerNode for '" + peerNode.getIdentityString() + "' with status '" + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()) + "'");
			statusSet.add(peerNode);
			statuses.put(peerNodeStatus, statusSet);
		}
	}

	/**
	 * How many PeerNodes have a particular status?
	 * @param darknet If true, only count darknet nodes, if false, count all nodes.
	 */
	public int getPeerNodeStatusSize(int pnStatus, boolean darknet) {
		Integer peerNodeStatus = new Integer(pnStatus);
		HashSet statusSet = null;
		HashMap statuses = darknet ? peerNodeStatusesDarknet : this.peerNodeStatuses;
		synchronized(statuses) {
			if(statuses.containsKey(peerNodeStatus))
				statusSet = (HashSet) statuses.get(peerNodeStatus);
			else
				statusSet = new HashSet();
			return statusSet.size();
		}
	}

	/**
	 * Remove a PeerNode status from the map
	 * @param isInPeers If true, complain if the node is not in the peers list; if false, complain if it is.
	 */
	public void removePeerNodeStatus(int pnStatus, PeerNode peerNode, boolean noLog) {
		Integer peerNodeStatus = new Integer(pnStatus);
		removePeerNodeStatus(pnStatus, peerNodeStatus, peerNode, peerNodeStatuses, noLog);
		if(!peerNode.isOpennet())
			removePeerNodeStatus(pnStatus, peerNodeStatus, peerNode, peerNodeStatusesDarknet, noLog);
	}

	private void removePeerNodeStatus(int pnStatus, Integer peerNodeStatus, PeerNode peerNode, HashMap statuses, boolean noLog) {
		HashSet statusSet = null;
		synchronized(statuses) {
			if(statuses.containsKey(peerNodeStatus)) {
				statusSet = (HashSet) statuses.get(peerNodeStatus);
				if(!statusSet.contains(peerNode)) {
					if(!noLog)
						Logger.error(this, "removePeerNodeStatus(): identity '" + peerNode.getIdentityString() + " for " + peerNode.shortToString() + "' not in peerNodeStatuses with status '" + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()) + "'", new Exception("debug"));
					return;
				}
				if(statuses.isEmpty())
					statuses.remove(peerNodeStatus);
			} else
				statusSet = new HashSet();
			if(logMINOR)
				Logger.minor(this, "removePeerNodeStatus(): removing PeerNode for '" + peerNode.getIdentityString() + "' with status '" + PeerNode.getPeerNodeStatusString(peerNodeStatus.intValue()) + "'");
			if(statusSet.contains(peerNode))
				statusSet.remove(peerNode);
		}
	}

	/**
	 * Add a PeerNode routing backoff reason to the map
	 */
	public void addPeerNodeRoutingBackoffReason(String peerNodeRoutingBackoffReason, PeerNode peerNode) {
		synchronized(peerNodeRoutingBackoffReasons) {
			HashSet reasonSet = null;
			if(peerNodeRoutingBackoffReasons.containsKey(peerNodeRoutingBackoffReason)) {
				reasonSet = (HashSet) peerNodeRoutingBackoffReasons.get(peerNodeRoutingBackoffReason);
				if(reasonSet.contains(peerNode)) {
					Logger.error(this, "addPeerNodeRoutingBackoffReason(): identity '" + peerNode.getIdentityString() + "' already in peerNodeRoutingBackoffReasons as " + peerNode.getPeer() + " with status code " + peerNodeRoutingBackoffReason);
					return;
				}
				peerNodeRoutingBackoffReasons.remove(peerNodeRoutingBackoffReason);
			} else
				reasonSet = new HashSet();
			if(logMINOR)
				Logger.minor(this, "addPeerNodeRoutingBackoffReason(): adding PeerNode for '" + peerNode.getIdentityString() + "' with status code " + peerNodeRoutingBackoffReason);
			reasonSet.add(peerNode);
			peerNodeRoutingBackoffReasons.put(peerNodeRoutingBackoffReason, reasonSet);
		}
	}

	/**
	 * What are the currently tracked PeerNode routing backoff reasons?
	 */
	public String[] getPeerNodeRoutingBackoffReasons() {
		String[] reasonStrings;
		synchronized(peerNodeRoutingBackoffReasons) {
			reasonStrings = (String[]) peerNodeRoutingBackoffReasons.keySet().toArray(new String[peerNodeRoutingBackoffReasons.size()]);
		}
		Arrays.sort(reasonStrings);
		return reasonStrings;
	}

	/**
	 * How many PeerNodes have a particular routing backoff reason?
	 */
	public int getPeerNodeRoutingBackoffReasonSize(String peerNodeRoutingBackoffReason) {
		HashSet reasonSet = null;
		synchronized(peerNodeRoutingBackoffReasons) {
			if(peerNodeRoutingBackoffReasons.containsKey(peerNodeRoutingBackoffReason)) {
				reasonSet = (HashSet) peerNodeRoutingBackoffReasons.get(peerNodeRoutingBackoffReason);
				return reasonSet.size();
			} else
				return 0;
		}
	}

	/**
	 * Remove a PeerNode routing backoff reason from the map
	 */
	public void removePeerNodeRoutingBackoffReason(String peerNodeRoutingBackoffReason, PeerNode peerNode) {
		HashSet reasonSet = null;
		synchronized(peerNodeRoutingBackoffReasons) {
			if(peerNodeRoutingBackoffReasons.containsKey(peerNodeRoutingBackoffReason)) {
				reasonSet = (HashSet) peerNodeRoutingBackoffReasons.get(peerNodeRoutingBackoffReason);
				if(!reasonSet.contains(peerNode)) {
					Logger.error(this, "removePeerNodeRoutingBackoffReason(): identity '" + peerNode.getIdentityString() + "' not in peerNodeRoutingBackoffReasons with status code " + peerNodeRoutingBackoffReason, new Exception("debug"));
					return;
				}
				peerNodeRoutingBackoffReasons.remove(peerNodeRoutingBackoffReason);
			} else
				reasonSet = new HashSet();
			if(logMINOR)
				Logger.minor(this, "removePeerNodeRoutingBackoffReason(): removing PeerNode for '" + peerNode.getIdentityString() + "' with status code " + peerNodeRoutingBackoffReason);
			if(reasonSet.contains(peerNode))
				reasonSet.remove(peerNode);
			if(reasonSet.size() > 0)
				peerNodeRoutingBackoffReasons.put(peerNodeRoutingBackoffReason, reasonSet);
		}
	}

	public PeerNodeStatus[] getPeerNodeStatuses(boolean noHeavy) {
		PeerNode[] peers;
		synchronized(this) {
			peers = myPeers;
		}
		PeerNodeStatus[] _peerNodeStatuses = new PeerNodeStatus[peers.length];
		for(int peerIndex = 0, peerCount = peers.length; peerIndex < peerCount; peerIndex++) {
			_peerNodeStatuses[peerIndex] = peers[peerIndex].getStatus(noHeavy);
		}
		return _peerNodeStatuses;
	}

	public DarknetPeerNodeStatus[] getDarknetPeerNodeStatuses(boolean noHeavy) {
		DarknetPeerNode[] peers = getDarknetPeers();
		DarknetPeerNodeStatus[] _peerNodeStatuses = new DarknetPeerNodeStatus[peers.length];
		for(int peerIndex = 0, peerCount = peers.length; peerIndex < peerCount; peerIndex++) {
			_peerNodeStatuses[peerIndex] = (DarknetPeerNodeStatus) peers[peerIndex].getStatus(noHeavy);
		}
		return _peerNodeStatuses;
	}

	public OpennetPeerNodeStatus[] getOpennetPeerNodeStatuses(boolean noHeavy) {
		OpennetPeerNode[] peers = getOpennetPeers();
		OpennetPeerNodeStatus[] _peerNodeStatuses = new OpennetPeerNodeStatus[peers.length];
		for(int peerIndex = 0, peerCount = peers.length; peerIndex < peerCount; peerIndex++) {
			_peerNodeStatuses[peerIndex] = (OpennetPeerNodeStatus) peers[peerIndex].getStatus(noHeavy);
		}
		return _peerNodeStatuses;
	}

	/**
	 * Update hadRoutableConnectionCount/routableConnectionCheckCount on peers if the timer has expired
	 */
	public void maybeUpdatePeerNodeRoutableConnectionStats(long now) {
		synchronized(this) {
			if(now <= nextRoutableConnectionStatsUpdateTime)
				return;
			nextRoutableConnectionStatsUpdateTime = now + routableConnectionStatsUpdateInterval;
		}
		if(-1 != nextRoutableConnectionStatsUpdateTime) {
			PeerNode[] peerList = myPeers;
			for(int i = 0; i < peerList.length; i++) {
				PeerNode pn = peerList[i];
				pn.checkRoutableConnectionStatus();
			}
		}
	}

	/**
	 * Get the darknet peers list.
	 * FIXME: optimise
	 */
	public DarknetPeerNode[] getDarknetPeers() {
		PeerNode[] peers;
		synchronized(this) {
			peers = myPeers;
		}
		// FIXME optimise! Maybe maintain as a separate list?
		Vector v = new Vector(myPeers.length);
		for(int i = 0; i < peers.length; i++) {
			if(peers[i] instanceof DarknetPeerNode)
				v.add(peers[i]);
		}
		return (DarknetPeerNode[]) v.toArray(new DarknetPeerNode[v.size()]);
	}

	public Vector getConnectedSeedServerPeersVector(HashSet exclude) {
		PeerNode[] peers;
		synchronized(this) {
			peers = myPeers;
		}
		// FIXME optimise! Maybe maintain as a separate list?
		Vector v = new Vector(myPeers.length);
		for(int i = 0; i < peers.length; i++) {
			if(peers[i] instanceof SeedServerPeerNode) {
				if(exclude != null && exclude.contains(peers[i].getIdentity())) {
					if(logMINOR)
						Logger.minor(this, "Not including in getConnectedSeedServerPeersVector() as in exclude set: " + peers[i].userToString());
					continue;
				}
				if(!peers[i].isConnected()) {
					if(logMINOR)
						Logger.minor(this, "Not including in getConnectedSeedServerPeersVector() as disconnected: " + peers[i].userToString());
					continue;
				}
				v.add(peers[i]);
			}
		}
		return v;
	}

	public Vector getSeedServerPeersVector() {
		PeerNode[] peers;
		synchronized(this) {
			peers = myPeers;
		}
		// FIXME optimise! Maybe maintain as a separate list?
		Vector v = new Vector(myPeers.length);
		for(int i = 0; i < peers.length; i++) {
			if(peers[i] instanceof SeedServerPeerNode)
				v.add(peers[i]);
		}
		return v;
	}

	/**
	 * Get the opennet peers list.
	 */
	public OpennetPeerNode[] getOpennetPeers() {
		PeerNode[] peers;
		synchronized(this) {
			peers = myPeers;
		}
		// FIXME optimise! Maybe maintain as a separate list?
		Vector v = new Vector(myPeers.length);
		for(int i = 0; i < peers.length; i++) {
			if(peers[i] instanceof OpennetPeerNode)
				v.add(peers[i]);
		}
		return (OpennetPeerNode[]) v.toArray(new OpennetPeerNode[v.size()]);
	}

	public boolean anyConnectedPeerHasAddress(FreenetInetAddress addr, PeerNode pn) {
		PeerNode[] peers;
		synchronized(this) {
			peers = myPeers;
		}
		for(int i = 0; i < peers.length; i++) {
			if(peers[i] == pn)
				continue;
			if(!peers[i].isConnected())
				continue;
			if(!peers[i].isRealConnection())
				continue; // Ignore non-searchable peers i.e. bootstrapping peers
			// If getPeer() is null then presumably !isConnected().
			if(peers[i].getPeer().getFreenetAddress().equals(addr))
				return true;
		}
		return false;
	}

	public void removeOpennetPeers() {
		synchronized(this) {
			Vector keep = new Vector();
			Vector conn = new Vector();
			for(int i = 0; i < myPeers.length; i++) {
				PeerNode pn = myPeers[i];
				if(pn instanceof OpennetPeerNode)
					continue;
				keep.add(pn);
				if(pn.isConnected())
					conn.add(pn);
			}
			myPeers = (PeerNode[]) keep.toArray(new PeerNode[keep.size()]);
			connectedPeers = (PeerNode[]) keep.toArray(new PeerNode[conn.size()]);
		}
		updatePMUserAlert();
	}

	public PeerNode containsPeer(PeerNode pn) {
		PeerNode[] peers = pn.isOpennet() ? ((PeerNode[]) getOpennetPeers()) : ((PeerNode[]) getDarknetPeers());

		for(int i = 0; i < peers.length; i++)
			if(Arrays.equals(pn.getIdentity(), peers[i].getIdentity()))
				return peers[i];

		return null;
	}

	public int quickCountConnectedPeers() {
		PeerNode[] conns = connectedPeers;
		if(conns == null)
			return 0;
		return connectedPeers.length;
	}

	public int countConnectedDarknetPeers() {
		int count = 0;
		PeerNode[] peers = myPeers;
		for(int i = 0; i < peers.length; i++) {
			if(peers[i] == null)
				continue;
			if(!(peers[i] instanceof DarknetPeerNode))
				continue;
			if(peers[i].isOpennet())
				continue;
			if(!peers[i].isRoutable())
				continue;
			count++;
		}
		return count;
	}

	public int countConnectedPeers() {
		int count = 0;
		PeerNode[] peers = myPeers;
		for(int i = 0; i < peers.length; i++) {
			if(peers[i] == null)
				continue;
			if(!peers[i].isRoutable())
				continue;
			count++;
		}
		return count;
	}

	public int countAlmostConnectedDarknetPeers() {
		int count = 0;
		PeerNode[] peers = myPeers;
		for(int i = 0; i < peers.length; i++) {
			if(peers[i] == null)
				continue;
			if(!(peers[i] instanceof DarknetPeerNode))
				continue;
			if(peers[i].isOpennet())
				continue;
			if(!peers[i].isConnected())
				continue;
			count++;
		}
		return count;
	}

	public int countCompatibleDarknetPeers() {
		int count = 0;
		PeerNode[] peers = myPeers;
		for(int i = 0; i < peers.length; i++) {
			if(peers[i] == null)
				continue;
			if(!(peers[i] instanceof DarknetPeerNode))
				continue;
			if(peers[i].isOpennet())
				continue;
			if(!peers[i].isConnected())
				continue;
			if(!peers[i].isRoutingCompatible())
				continue;
			count++;
		}
		return count;
	}

	public int countConnectedOpennetPeers() {
		int count = 0;
		PeerNode[] peers = connectedPeers;
		for(int i = 0; i < peers.length; i++) {
			if(peers[i] == null)
				continue;
			if(!(peers[i] instanceof OpennetPeerNode))
				continue;
			if(!peers[i].isRoutable())
				continue;
			count++;
		}
		return count;
	}

	/**
	 * How many peers do we have that actually may connect? Don't include seednodes, disabled nodes, etc.
	 */
	public int countValidPeers() {
		PeerNode[] peers = myPeers;
		int count = 0;
		for(int i = 0; i < peers.length; i++) {
			if(!peers[i].isRealConnection())
				continue;
			if(peers[i].isDisabled())
				continue;
			count++;
		}
		return count;
	}
	
	public int countSeednodes() {
		int count = 0;
		for(PeerNode peer : myPeers) {
			if(peer.isRealConnection())
				continue;
			count++;
		}
		return count;
	}

	public int countBackedOffPeers() {
		PeerNode[] peers = myPeers;
		int count = 0;
		for(int i = 0; i < peers.length; i++) {
			if(!peers[i].isRealConnection())
				continue;
			if(peers[i].isDisabled())
				continue;
			if(peers[i].isRoutingBackedOff())
				count++;
		}
		return count;
	}

	public PeerNode getByIdentity(byte[] identity) {
		PeerNode[] peers = myPeers;
		for(int i = 0; i < peers.length; i++) {
			if(Arrays.equals(peers[i].getIdentity(), identity))
				return peers[i];
		}
		return null;
	}
}
