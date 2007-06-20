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
import java.util.Vector;
import java.util.ArrayList;

import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.node.useralerts.PeerManagerUserAlert;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

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
    
    final String filename;

    private PeerManagerUserAlert ua;
    
	// Peers stuff
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
	
    /**
     * Create a PeerManager by reading a list of peers from
     * a file.
     * @param node
     * @param filename
     */
    public PeerManager(Node node, String filename) {
        Logger.normal(this, "Creating PeerManager");
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
		peerNodeStatuses = new HashMap();
		peerNodeRoutingBackoffReasons = new HashMap();
        System.out.println("Creating PeerManager");
        this.filename = filename;
        myPeers = new PeerNode[0];
        connectedPeers = new PeerNode[0];
        this.node = node;
        File peersFile = new File(filename);
        File backupFile = new File(filename+".bak");
        // Try to read the node list from disk
     	if(peersFile.exists()) {
      		if(readPeers(peersFile)) {
      			String msg = "Read "+myPeers.length+" peers from "+peersFile;
      			Logger.normal(this, msg);
      			System.out.println(msg);
      			return;
      		}
       	}
     	// Try the backup
     	if(backupFile.exists()) {
        	if(readPeers(backupFile)) {
      			String msg = "Read "+myPeers.length+" peers from "+backupFile;
      			Logger.normal(this, msg);
      			System.out.println(msg);
        	} else {
        		Logger.error(this, "No (readable) peers file with peers in it found");
        		System.err.println("No (readable) peers file with peers in it found");
        	}
     	}     		
    }

    private boolean readPeers(File peersFile) {
    	boolean gotSome = false;
    	FileInputStream fis;
		try {
			fis = new FileInputStream(peersFile);
		} catch (FileNotFoundException e4) {
			Logger.normal(this, "Peers file not found: "+peersFile);
			return false;
		}
        InputStreamReader ris;
		try {
			ris = new InputStreamReader(fis, "UTF-8");
		} catch (UnsupportedEncodingException e4) {
			throw new Error("UTF-8 not supported!: "+e4, e4);
		}
        BufferedReader br = new BufferedReader(ris);
        try { // FIXME: no better way?
            while(true) {
                // Read a single NodePeer
                SimpleFieldSet fs;
                fs = new SimpleFieldSet(br, false, true);
                PeerNode pn;
                try {
                    pn = new PeerNode(fs, node, this, true);
                } catch (FSParseException e2) {
                    Logger.error(this, "Could not parse peer: "+e2+ '\n' +fs.toString(),e2);
                    continue;
                } catch (PeerParseException e2) {
                    Logger.error(this, "Could not parse peer: "+e2+ '\n' +fs.toString(),e2);
                    continue;
                } catch (ReferenceSignatureVerificationException e2) {
                	Logger.error(this, "Could not parse peer: "+e2+ '\n' +fs.toString(),e2);
                    continue;
				}
                addPeer(pn);
                gotSome = true;
            }
        } catch (EOFException e) {
            // End of file, fine
        } catch (IOException e1) {
            Logger.error(this, "Could not read peers file: "+e1, e1);
        }
        try {
        	br.close();
        } catch (IOException e3) {
        	Logger.error(this, "Ignoring "+e3+" caught reading "+filename, e3);
        }
        return gotSome;
	}

	public boolean addPeer(PeerNode pn) {
    	synchronized(this) {
        for(int i=0;i<myPeers.length;i++) {
            if(myPeers[i].equals(pn)) {
            	return false;
            }
        }
        PeerNode[] newMyPeers = new PeerNode[myPeers.length+1];
        System.arraycopy(myPeers, 0, newMyPeers, 0, myPeers.length);
        newMyPeers[myPeers.length] = pn;
        myPeers = newMyPeers;
        Logger.normal(this, "Added "+pn);
    	}
        updatePMUserAlert();
        return true;
    }
    
    private boolean removePeer(PeerNode pn) {
    	synchronized(this) {
    	boolean isInPeers = false;
        for(int i=0;i<myPeers.length;i++) {
            if(myPeers[i] == pn) isInPeers=true;
        }
        int peerNodeStatus = pn.getPeerNodeStatus();
        removePeerNodeStatus( peerNodeStatus, pn );
        String peerNodePreviousRoutingBackoffReason = pn.getPreviousBackoffReason();
        if(peerNodePreviousRoutingBackoffReason != null) {
        	removePeerNodeRoutingBackoffReason(peerNodePreviousRoutingBackoffReason, pn);
        }
        pn.removeExtraPeerDataDir();
        if(!isInPeers) return false;
                
        // removing from connectedPeers
        ArrayList a = new ArrayList();
        for(int i=0;i<myPeers.length;i++) {
        	if((myPeers[i]!=pn) && myPeers[i].isRoutable())
        		a.add(myPeers[i]);
        }
        
        PeerNode[] newConnectedPeers = new PeerNode[a.size()];
        newConnectedPeers = (PeerNode[]) a.toArray(newConnectedPeers);
	    connectedPeers = newConnectedPeers;
        
        // removing from myPeers
        PeerNode[] newMyPeers = new PeerNode[myPeers.length-1];
        int positionInNewArray = 0;
        for(int i=0;i<myPeers.length;i++) {
        	if(myPeers[i]!=pn){
        		newMyPeers[positionInNewArray] = myPeers[i];
        		positionInNewArray++;
        	}
        }
        myPeers = newMyPeers;
        
        Logger.normal(this, "Removed "+pn);
    	}
        updatePMUserAlert();
        return true;
    }

	public boolean disconnected(PeerNode pn) {
		synchronized(this) {
			boolean isInPeers = false;
			for(int i=0;i<connectedPeers.length;i++) {
				if(connectedPeers[i] == pn) isInPeers=true;
			}
			if(!isInPeers) return false;
			// removing from connectedPeers
			ArrayList a = new ArrayList();
			for(int i=0;i<myPeers.length;i++) {
				if((myPeers[i]!=pn) && myPeers[i].isRoutable())
					a.add(myPeers[i]);
			}
			PeerNode[] newConnectedPeers = new PeerNode[a.size()];
			newConnectedPeers = (PeerNode[]) a.toArray(newConnectedPeers);
			connectedPeers = newConnectedPeers;
		}
		updatePMUserAlert();
		return true;
	}
	
    public void addConnectedPeer(PeerNode pn) {
    	logMINOR = Logger.shouldLog(Logger.MINOR, this);
    	if(!pn.isRoutable()) {
    		if(logMINOR) Logger.minor(this, "Not ReallyConnected: "+pn);
    		return;
    	}
    	synchronized(this) {
        for(int i=0;i<connectedPeers.length;i++) {
            if(connectedPeers[i] == pn) {
            	if(logMINOR) Logger.minor(this, "Already connected: "+pn);
                return;
            }
        }
        boolean inMyPeers = false;
        for(int i=0;i<myPeers.length;i++) {
            if(myPeers[i] == pn) {
                inMyPeers = true;
                break;
            }
        }
        if(!inMyPeers) {
            Logger.error(this, "Connecting to "+pn+" but not in peers!");
            addPeer(pn);
        }
        if(logMINOR) Logger.minor(this, "Connecting: "+pn);
        PeerNode[] newConnectedPeers = new PeerNode[connectedPeers.length+1];
        System.arraycopy(connectedPeers, 0, newConnectedPeers, 0, connectedPeers.length);
        newConnectedPeers[connectedPeers.length] = pn;
        connectedPeers = newConnectedPeers;
        if(logMINOR) Logger.minor(this, "Connected peers: "+connectedPeers.length);
    	}
        updatePMUserAlert();
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
        for(int i=0;i<myPeers.length;i++) {
            if(peer.equals(myPeers[i].getPeer()))
                return myPeers[i];
        }
        return null;
    }

    /**
     * Connect to a node provided the fieldset representing it.
     */
    public void connect(SimpleFieldSet noderef) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
        PeerNode pn = new PeerNode(noderef, node, this, false);
        for(int i=0;i<myPeers.length;i++) {
            if(Arrays.equals(myPeers[i].identity, pn.identity)) return;
        }
        addPeer(pn);
    }
       
    /**
     * Disconnect from a specified node
     */
    public void disconnect(PeerNode pn){
    	if(removePeer(pn))
    		writePeers();
    }

    class LocationUIDPair implements Comparable {
    	double location;
    	long uid;
    	
    	LocationUIDPair(PeerNode pn) {
    		location = pn.getLocation().getValue();
    		uid = pn.swapIdentifier;
    	}

		public int compareTo(Object arg0) {
			// Compare purely on location, so result is the same as getPeerLocationDoubles()
			LocationUIDPair p = (LocationUIDPair) arg0;
			if(p.location > location) return 1;
			if(p.location < location) return -1;
			return 0;
		}
    }
    
    /**
     * @return An array of the current locations (as doubles) of all
     * our connected peers.
     */
    public double[] getPeerLocationDoubles() {
        double[] locs;
        PeerNode[] conns;
        synchronized (this) {
			conns = connectedPeers;
		}
        locs = new double[conns.length];
        int x = 0;
        for(int i=0;i<conns.length;i++) {
            if(conns[i].isRoutable())
                locs[x++] = conns[i].getLocation().getValue();
        }
        // Wipe out any information contained in the order
        java.util.Arrays.sort(locs, 0, x);
        if(x != locs.length) {
            double[] newLocs = new double[x];
            System.arraycopy(locs, 0, newLocs, 0, x);
            return newLocs;
        } else return locs;
    }

    /** Just like getPeerLocationDoubles, except it also
     * returns the UID for each node. */
    public LocationUIDPair[] getPeerLocationsAndUIDs() {
        PeerNode[] conns;
        LocationUIDPair[] locPairs;
        synchronized (this) {
			conns = myPeers;
		}
        locPairs = new LocationUIDPair[conns.length];
        int x = 0;
        for(int i=0;i<conns.length;i++) {
            if(conns[i].isRoutable())
            	locPairs[x++] = new LocationUIDPair(conns[i]);
        }
        // Sort it
        Arrays.sort(locPairs, 0, x);
        if(x != locPairs.length) {
            LocationUIDPair[] newLocs = new LocationUIDPair[x];
            System.arraycopy(locPairs, 0, newLocs, 0, x);
            return newLocs;
        } else return locPairs;
    }

    /**
     * @return A random connected peer.
     * FIXME: should this take performance into account?
     * DO NOT remove the "synchronized". See below for why.
     */
    public synchronized PeerNode getRandomPeer(PeerNode exclude) {
        if(connectedPeers.length == 0) return null;
        for(int i=0;i<5;i++) {
            PeerNode pn = connectedPeers[node.random.nextInt(connectedPeers.length)];
            if(pn == exclude) continue;
            if(pn.isRoutable()) return pn;
        }
        // None of them worked
        // Move the un-connected ones out
        // This is safe as they will add themselves when they
        // reconnect, and they can't do it yet as we are synchronized.
        Vector v = new Vector(connectedPeers.length);
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
        for(int i=0;i<myPeers.length;i++) {
            PeerNode pn = myPeers[i];
            if(pn == exclude) continue;
            if(pn.isRoutable()) {
                v.add(pn);
            } else {
            	if(logMINOR) Logger.minor(this, "Excluding "+pn+" because is disconnected");
            }
        }
        int lengthWithoutExcluded = v.size();
        if((exclude != null) && exclude.isRoutable())
            v.add(exclude);
        PeerNode[] newConnectedPeers = new PeerNode[v.size()];
        newConnectedPeers = (PeerNode[]) v.toArray(newConnectedPeers);
        if(logMINOR) Logger.minor(this, "Connected peers (in getRandomPeer): "+newConnectedPeers.length+" was "+connectedPeers.length);
        connectedPeers = newConnectedPeers;
        if(lengthWithoutExcluded == 0) return null;
        return connectedPeers[node.random.nextInt(lengthWithoutExcluded)];
    }

    /**
     * Asynchronously send this message to every connected peer.
     */
    public void localBroadcast(Message msg, boolean ignoreRoutability) {
        PeerNode[] peers;
        synchronized (this) {
        	// myPeers not connectedPeers as connectedPeers only contains
        	// ROUTABLE peers, and we may want to send to non-routable peers
			peers = myPeers;
		}
        for(int i=0;i<peers.length;i++) {
        	if(ignoreRoutability) {
        		if(!peers[i].isConnected()) continue;
        	} else {
        		if(!peers[i].isRoutable()) continue;
        	}
        	try {
                peers[i].sendAsync(msg, null, 0, null);
            } catch (NotConnectedException e) {
                // Ignore
            }
        }
    }

    public PeerNode getRandomPeer() {
        return getRandomPeer(null);
    }

    public double closestPeerLocation(double loc, double ignoreLoc) {
        PeerNode[] peers;
        synchronized (this) {
        	peers = connectedPeers;
		}
        double bestDiff = 1.0;
        double bestLoc = Double.MAX_VALUE;
        boolean foundOne = false;
        for(int i=0;i<peers.length;i++) {
            PeerNode p = peers[i];
            if(!p.isRoutable()) continue;
            if(p.isRoutingBackedOff()) continue;
            double peerloc = p.getLocation().getValue();
            if(Math.abs(peerloc - ignoreLoc) < Double.MIN_VALUE*2)
            	continue;
            double diff = distance(peerloc, loc);
            if(diff < bestDiff) {
            	foundOne = true;
                bestDiff = diff;
                bestLoc = peerloc;
            }
        }
        if(!foundOne) {
            for(int i=0;i<peers.length;i++) {
                PeerNode p = peers[i];
                if(!p.isRoutable()) continue;
                // Ignore backoff state
                double peerloc = p.getLocation().getValue();
                if(Math.abs(peerloc - ignoreLoc) < Double.MIN_VALUE*2)
                	continue;
                double diff = distance(peerloc, loc);
                if(diff < bestDiff) {
                	foundOne = true;
                    bestDiff = diff;
                    bestLoc = peerloc;
                }
            }
        }
        return bestLoc;
    }

    public boolean isCloserLocation(double loc) {
    	double nodeLoc = node.lm.getLocation().getValue();
    	double nodeDist = distance(nodeLoc, loc);
    	double closest = closestPeerLocation(loc, nodeLoc);
    	if(closest > 1.0) {
    		// No peers found
    		return false;
    	}
    	double closestDist = distance(closest, loc);
    	return closestDist < nodeDist;
    }
    
    static double distance(PeerNode p, double loc) {
    	double d = distance(p.getLocation().getValue(), loc);
    	return d;
    	//return d * p.getBias();
    }
    
    /**
     * Distance between two locations.
     * Both parameters must be in [0.0, 1.0].
     */
    public static double distance(double a, double b) {
    	return distance(a, b, false);
    }
    
    public static double distance(double a, double b, boolean allowCrazy) {
        if(((a < 0.0 || a > 1.0)||(b < 0.0 || b > 1.0)) && !allowCrazy) {
        	Logger.error(PeerManager.class, "Invalid Location ! a = "+a +" b = "+ b + "Please report this bug!", new Exception("error"));
        	throw new NullPointerException();
        }
        // Circular keyspace
    	if (a > b) return Math.min (a - b, 1.0 - a + b);
    	else return Math.min (b - a, 1.0 - b + a);
    }

    public PeerNode closerPeer(PeerNode pn, HashSet routedTo, HashSet notIgnored, double loc, boolean ignoreSelf, boolean calculateMisrouting, int minVersion, Vector addUnpickedLocsTo) {
    	return closerPeer(pn, routedTo, notIgnored, loc, ignoreSelf, calculateMisrouting, minVersion, addUnpickedLocsTo, 2.0);
    }
    
    /*
     * FIXME
     * This scans the same array 4 times.  It would be better to scan once and execute 4 callbacks...
     * For this reason the metrics are only updated if advanced mode is enabled
     */
    public PeerNode closerPeer(PeerNode pn, HashSet routedTo, HashSet notIgnored, double loc, boolean ignoreSelf, boolean calculateMisrouting, int minVersion, Vector addUnpickedLocsTo, double maxDistance) {
    	PeerNode best = closerPeerBackoff(pn, routedTo, notIgnored, loc, ignoreSelf, minVersion, addUnpickedLocsTo, maxDistance);
    		
    	if (calculateMisrouting) {
    		PeerNode nbo = _closerPeer(pn, routedTo, notIgnored, loc, ignoreSelf, true, minVersion, null, maxDistance);
    		if(nbo != null) {
    			node.nodeStats.routingMissDistance.report(distance(best, nbo.getLocation().getValue()));
    			int numberOfConnected = getPeerNodeStatusSize(PEER_NODE_STATUS_CONNECTED);
    			int numberOfRoutingBackedOff = getPeerNodeStatusSize(PEER_NODE_STATUS_ROUTING_BACKED_OFF);
    			if (numberOfRoutingBackedOff + numberOfConnected > 0 ) {
    				node.nodeStats.backedOffPercent.report((double) numberOfRoutingBackedOff / (double) (numberOfRoutingBackedOff + numberOfConnected));
    			}
    		}
    	}
    	return best;
    }
	    
    private PeerNode closerPeerBackoff(PeerNode pn, HashSet routedTo, HashSet notIgnored, double loc, boolean ignoreSelf, int minVersion, Vector addUnpickedLocsTo, double maxDistance) {
    	PeerNode best = _closerPeer(pn, routedTo, notIgnored, loc, ignoreSelf, false, minVersion, addUnpickedLocsTo, maxDistance);
    	if(best == null) {
    		best = _closerPeer(pn, routedTo, notIgnored, loc, ignoreSelf, true, minVersion, addUnpickedLocsTo, maxDistance);
    	}
    	return best;
	}

	/**
     * Find the peer, if any, which is closer to the target location
     * than we are, and is not included in the provided set.
	 * @param addUnpickedLocsTo Add all locations we didn't choose which we could have routed to to 
	 * this array. Remove the location of the peer we pick from it.
     */
    private PeerNode _closerPeer(PeerNode pn, HashSet routedTo, HashSet notIgnored, double target, boolean ignoreSelf, boolean ignoreBackedOff, int minVersion, Vector addUnpickedLocsTo, double maxDistance) {
        PeerNode[] peers;  
        synchronized (this) {
			peers = connectedPeers;
		}
        if(logMINOR) Logger.minor(this, "Choosing closest peer: connectedPeers="+peers.length);
        double bestDiff = Double.MAX_VALUE;
        double maxDiff = 0.0;
        if(!ignoreSelf)
            maxDiff = distance(node.lm.getLocation().getValue(), target);
        PeerNode best = null;
        double bestLoc = -2;
        int count = 0;
        for(int i=0;i<peers.length;i++) {
            PeerNode p = peers[i];
            if(routedTo.contains(p)) {
            	if(logMINOR) Logger.minor(this, "Skipping (already routed to): "+p.getPeer());
            	continue;
            }
            if(p == pn) {
            	if(logMINOR) Logger.minor(this, "Skipping (req came from): "+p.getPeer());
            	continue;
            }
            if(!p.isRoutable()) {
            	if(logMINOR) Logger.minor(this, "Skipping (not connected): "+p.getPeer());
            	continue;
            }
            if((!ignoreBackedOff) && p.isRoutingBackedOff()) {
            	if(logMINOR) Logger.minor(this, "Skipping (routing backed off): "+p.getPeer());
            	continue;
            }
            if(minVersion > 0 && Version.getArbitraryBuildNumber(p.getVersion()) < minVersion) {
            	if(logMINOR) Logger.minor(this, "Skipping old version: "+p.getPeer());
            	continue;
            }
            count++;
            double diff = distance(p, target);
            if(diff > maxDistance) continue;
            if(logMINOR) Logger.minor(this, "p.loc="+p.getLocation().getValue()+", target="+target+", d="+distance(p.getLocation().getValue(), target)+" usedD="+diff+" for "+p.getPeer());
            if((!ignoreSelf) && (diff > maxDiff)) {
            	if(logMINOR) Logger.minor(this, "Ignoring because >maxDiff="+maxDiff);
            	continue;
            }
            double loc = p.getLocation().getValue();
            if(diff < bestDiff) {
            	bestLoc = loc;
                best = p;
                bestDiff = diff;
                if(logMINOR) Logger.minor(this, "New best: "+diff+" ("+p.getLocation().getValue()+" for "+p.getPeer());
            } else {
            	if(addUnpickedLocsTo != null) {
            		Double d = new Double(loc);
            		// Here we can directly compare double's because they aren't processed in any way, and are finite and (probably) nonzero.
            		if(!addUnpickedLocsTo.contains(d))
            			addUnpickedLocsTo.add(d);
            		
            	}
            }
        }
        if(addUnpickedLocsTo != null && bestLoc >= 0)
        	addUnpickedLocsTo.remove(new Double(bestLoc));
        return best;
    }

    /**
     * @return Some status information
     */
    public String getStatus() {
        StringBuffer sb = new StringBuffer();
        PeerNode[] peers;
        synchronized (this) {
            peers = myPeers;			
		}
        String[] status = new String[peers.length];
        for(int i=0;i<peers.length;i++) {
            PeerNode pn = peers[i];
            status[i] = pn.getStatus().toString();
	    Version.seenVersion(pn.getVersion());
        }
        Arrays.sort(status);
        for(int i=0;i<status.length;i++) {
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
        synchronized (this) {
			peers = myPeers;
		}
        String[] peerList = new String[peers.length];
        for(int i=0;i<peers.length;i++) {
            PeerNode pn = peers[i];
            peerList[i] = pn.getTMCIPeerInfo();
        }
        Arrays.sort(peerList);
        for(int i=0;i<peerList.length;i++) {
            sb.append(peerList[i]);
            sb.append('\n');
        }
        return sb.toString();
    }

    public String getFreevizOutput() {
        StringBuffer sb = new StringBuffer();
        PeerNode[] peers;
        synchronized (this) {
			peers = myPeers;
		}
        String[] identity = new String[peers.length];
        for(int i=0;i<peers.length;i++) {
            PeerNode pn = peers[i];
            identity[i] = pn.getFreevizOutput();
        }
        Arrays.sort(identity);
        for(int i=0;i<identity.length;i++) {
            sb.append(identity[i]);
            sb.append('\n');
        }
        return sb.toString();
    }

    final Object writePeersSync = new Object();
    
    void writePeers() {
    	node.ps.queueTimedJob(new Runnable() {
			public void run() {
				writePeersInner();
			}
    	}, 0);
    }
    
    /**
     * Write the peers file to disk
     */
    private void writePeersInner() {
        synchronized (writePeersSync) {
            FileOutputStream fos;
            String f = filename + ".bak";
            try {
                fos = new FileOutputStream(f);
            } catch (FileNotFoundException e2) {
                Logger.error(this, "Cannot write peers to disk: Cannot create "
                        + f + " - " + e2, e2);
                return;
            }
            OutputStreamWriter w;
			try {
				w = new OutputStreamWriter(fos, "UTF-8");
			} catch (UnsupportedEncodingException e2) {
				throw new Error("UTF-8 unsupported!: "+e2, e2);
			}
            BufferedWriter bw = new BufferedWriter(w);
            try {
            	boolean succeeded = writePeers(bw);
                bw.close();
                if(!succeeded) return;
            } catch (IOException e) {
            	try {
            		fos.close();
            	} catch (IOException e1) {
            		Logger.error(this, "Cannot close peers file: "+e, e);
            	}
                Logger.error(this, "Cannot write file: " + e, e);
                return; // don't overwrite old file!
            }
            if (!new File(f).renameTo(new File(filename))) {
                new File(filename).delete();
                if (!new File(f).renameTo(new File(filename))) {
                    Logger.error(this, "Could not rename " + f + " to "
                            + filename + " writing peers");
                }
            }
        }
    }

	public boolean writePeers(Writer bw) {
        PeerNode[] peers;
        synchronized (this) {
			peers = myPeers;
		}
        for (int i = 0; i < peers.length; i++) {
            try {
                peers[i].write(bw);
                bw.flush();
            } catch (IOException e) {
                try {
                    bw.close();
                } catch (IOException e1) {
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
		if(ua == null) return;
		int conns, peers;
		synchronized(this) {
			conns = this.connectedPeers.length;
			peers = this.myPeers.length;
		}
		synchronized(ua) {
			ua.conns = conns;
			ua.peers = peers;
			ua.neverConn = getPeerNodeStatusSize(PEER_NODE_STATUS_NEVER_CONNECTED);
		}
		if(anyConnectedPeers())
			node.onConnectedPeer();
	}

	public boolean anyConnectedPeers() {
		PeerNode[] conns;
		synchronized (this) {
			conns = connectedPeers;
		}
		for(int i=0;i<conns.length;i++) {
			if(conns[i].isRoutable()) return true;
		}
		return false;
	}

	/**
	 * Ask each PeerNode to read in it's extra peer data
	 */
	public void readExtraPeerData() {
		PeerNode[] peers;
		synchronized (this) {
			peers = myPeers;
		}
		for (int i = 0; i < peers.length; i++) {
			try {
				peers[i].readExtraPeerData();
			} catch (Exception e) {
				Logger.error(this, "Got exception while reading extra peer data", e);
			}
		}
		String msg = "Extra peer data reading and processing completed";
		Logger.normal(this, msg);
		System.out.println(msg);
	}

	public void start() {
        ua = new PeerManagerUserAlert(node.nodeStats);
		node.clientCore.alerts.register(ua);
	}

	public int countNonBackedOffPeers() {
		PeerNode[] peers;
		synchronized(this) {
			peers = connectedPeers; // even if myPeers peers are connected they won't be routed to
		}
		int countNoBackoff = 0;
		for(int i=0;i<peers.length;i++) {
			if(peers[i].isRoutable()) {
				if(!peers[i].isRoutingBackedOff()) countNoBackoff++;
			}
		}
		return countNoBackoff;
	}
	
	// Stats stuff
	
	/**
	 * Update oldestNeverConnectedPeerAge if the timer has expired
	 */
	public void maybeUpdateOldestNeverConnectedPeerAge(long now) {
		synchronized(this) {
			if(now <= nextOldestNeverConnectedPeerAgeUpdateTime) return;
			nextOldestNeverConnectedPeerAgeUpdateTime = now + oldestNeverConnectedPeerAgeUpdateInterval;
		}
		oldestNeverConnectedPeerAge = 0;
		PeerNode[] peerList = myPeers;
		for(int i=0;i<peerList.length;i++) {
			PeerNode pn = peerList[i];
			if(pn.getPeerNodeStatus() == PEER_NODE_STATUS_NEVER_CONNECTED) {
				if((now - pn.getPeerAddedTime()) > oldestNeverConnectedPeerAge) {
					oldestNeverConnectedPeerAge = now - pn.getPeerAddedTime();
				}
			}
		}
		if(oldestNeverConnectedPeerAge > 0 && logMINOR)
			Logger.minor(this, "Oldest never connected peer is "+oldestNeverConnectedPeerAge+"ms old");
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
		if((now - nextPeerNodeStatusLogTime) > (10*1000) && nextPeerNodeStatusLogTime > 0)
		  Logger.error(this,"maybeLogPeerNodeStatusSummary() not called for more than 10 seconds ("+(now - nextPeerNodeStatusLogTime)+").  PacketSender getting bogged down or something?");
		
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
		
		PeerNodeStatus[] pns = getPeerNodeStatuses();
		
		for(int i=0; i<pns.length; i++){
			switch (pns[i].getStatusValue()) {
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
			default:
				Logger.error(this, "Unknown peer status value : "+pns[i].getStatusValue());
				break;
			}
		}
		Logger.normal(this, "Connected: "+numberOfConnected+"  Routing Backed Off: "+numberOfRoutingBackedOff+"  Too New: "+numberOfTooNew+"  Too Old: "+numberOfTooOld+"  Disconnected: "+numberOfDisconnected+"  Never Connected: "+numberOfNeverConnected+"  Disabled: "+numberOfDisabled+"  Bursting: "+numberOfBursting+"  Listening: "+numberOfListening+"  Listen Only: "+numberOfListenOnly);
		nextPeerNodeStatusLogTime = now + peerNodeStatusLogInterval;
		}
	}

	/**
	 * Add a PeerNode status to the map
	 */
	public void addPeerNodeStatus(int pnStatus, PeerNode peerNode) {
		Integer peerNodeStatus = new Integer(pnStatus);
		HashSet statusSet = null;
		synchronized(peerNodeStatuses) {
			if(peerNodeStatuses.containsKey(peerNodeStatus)) {
				statusSet = (HashSet) peerNodeStatuses.get(peerNodeStatus);
				if(statusSet.contains(peerNode)) {
					Logger.error(this, "addPeerNodeStatus(): identity '"+peerNode.getIdentityString()+"' already in peerNodeStatuses as "+peerNode+" with status code "+peerNodeStatus);
					return;
				}
				peerNodeStatuses.remove(peerNodeStatus);
			} else {
				statusSet = new HashSet();
			}
			if(logMINOR) Logger.minor(this, "addPeerNodeStatus(): adding PeerNode for '"+peerNode.getIdentityString()+"' with status code "+peerNodeStatus);
			statusSet.add(peerNode);
			peerNodeStatuses.put(peerNodeStatus, statusSet);
		}
	}

	/**
	 * How many PeerNodes have a particular status?
	 */
	public int getPeerNodeStatusSize(int pnStatus) {
		Integer peerNodeStatus = new Integer(pnStatus);
		HashSet statusSet = null;
		synchronized(peerNodeStatuses) {
			if(peerNodeStatuses.containsKey(peerNodeStatus)) {
				statusSet = (HashSet) peerNodeStatuses.get(peerNodeStatus);
			} else {
				statusSet = new HashSet();
			}
			return statusSet.size();
		}
	}

	/**
	 * Remove a PeerNode status from the map
	 */
	public void removePeerNodeStatus(int pnStatus, PeerNode peerNode) {
		Integer peerNodeStatus = new Integer(pnStatus);
		HashSet statusSet = null;
		synchronized(peerNodeStatuses) {
			if(peerNodeStatuses.containsKey(peerNodeStatus)) {
				statusSet = (HashSet) peerNodeStatuses.get(peerNodeStatus);
				if(!statusSet.contains(peerNode)) {
					Logger.error(this, "removePeerNodeStatus(): identity '"+peerNode.getIdentityString()+"' not in peerNodeStatuses with status code "+peerNodeStatus);
					return;
				}
				peerNodeStatuses.remove(peerNodeStatus);
			} else {
				statusSet = new HashSet();
			}
			if(logMINOR) Logger.minor(this, "removePeerNodeStatus(): removing PeerNode for '"+peerNode.getIdentityString()+"' with status code "+peerNodeStatus);
			if(statusSet.contains(peerNode)) {
				statusSet.remove(peerNode);
			}
			peerNodeStatuses.put(peerNodeStatus, statusSet);
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
					Logger.error(this, "addPeerNodeRoutingBackoffReason(): identity '"+peerNode.getIdentityString()+"' already in peerNodeRoutingBackoffReasons as "+peerNode+" with status code "+peerNodeRoutingBackoffReason);
					return;
				}
				peerNodeRoutingBackoffReasons.remove(peerNodeRoutingBackoffReason);
			} else {
				reasonSet = new HashSet();
			}
			if(logMINOR) Logger.minor(this, "addPeerNodeRoutingBackoffReason(): adding PeerNode for '"+peerNode.getIdentityString()+"' with status code "+peerNodeRoutingBackoffReason);
			reasonSet.add(peerNode);
			peerNodeRoutingBackoffReasons.put(peerNodeRoutingBackoffReason, reasonSet);
		}
	}
	
	/**
	 * What are the currently tracked PeerNode routing backoff reasons?
	 */
	public String [] getPeerNodeRoutingBackoffReasons() {
		String [] reasonStrings;
		synchronized(peerNodeRoutingBackoffReasons) {
			reasonStrings = (String []) peerNodeRoutingBackoffReasons.keySet().toArray(new String[peerNodeRoutingBackoffReasons.size()]);
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
			} else {
				return 0;
			}
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
					Logger.error(this, "removePeerNodeRoutingBackoffReason(): identity '"+peerNode.getIdentityString()+"' not in peerNodeRoutingBackoffReasons with status code "+peerNodeRoutingBackoffReason);
					return;
				}
				peerNodeRoutingBackoffReasons.remove(peerNodeRoutingBackoffReason);
			} else {
				reasonSet = new HashSet();
			}
			if(logMINOR) Logger.minor(this, "removePeerNodeRoutingBackoffReason(): removing PeerNode for '"+peerNode.getIdentityString()+"' with status code "+peerNodeRoutingBackoffReason);
			if(reasonSet.contains(peerNode)) {
				reasonSet.remove(peerNode);
			}
			if(reasonSet.size() > 0) {
				peerNodeRoutingBackoffReasons.put(peerNodeRoutingBackoffReason, reasonSet);
			}
		}
	}

	public PeerNodeStatus[] getPeerNodeStatuses() {
        PeerNode[] peers;
        synchronized (this) {
			peers = myPeers;
		}
		PeerNodeStatus[] peerNodeStatuses = new PeerNodeStatus[peers.length];
		for (int peerIndex = 0, peerCount = peers.length; peerIndex < peerCount; peerIndex++) {
			peerNodeStatuses[peerIndex] = peers[peerIndex].getStatus();
		}
		return peerNodeStatuses;
	}

	/**
	 * Update hadRoutableConnectionCount/routableConnectionCheckCount on peers if the timer has expired
	 */
	public void maybeUpdatePeerNodeRoutableConnectionStats(long now) {
		synchronized(this) {
			if(now <= nextRoutableConnectionStatsUpdateTime) return;
			nextRoutableConnectionStatsUpdateTime = now + routableConnectionStatsUpdateInterval;
		}
	 	if(-1 != nextRoutableConnectionStatsUpdateTime) {
			PeerNode[] peerList = myPeers;
			for(int i=0;i<peerList.length;i++) {
				PeerNode pn = peerList[i];
				pn.checkRoutableConnectionStatus();
			}
	 	}
	}

}
