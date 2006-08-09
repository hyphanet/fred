package freenet.node;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Vector;
import java.util.ArrayList;

import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
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
    
    /** Our Node */
    final Node node;
    
    /** All the peers we want to connect to */
    PeerNode[] myPeers;
    
    /** All the peers we are actually connected to */
    PeerNode[] connectedPeers;
    
    final String filename;

    private final PeerManagerUserAlert ua;
    
    /**
     * Create a PeerManager by reading a list of peers from
     * a file.
     * @param node
     * @param filename
     */
    public PeerManager(Node node, String filename) {
        Logger.normal(this, "Creating PeerManager");
        System.out.println("Creating PeerManager");
        this.filename = filename;
        ua = new PeerManagerUserAlert(node);
        node.alerts.register(ua);
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
        InputStreamReader ris = new InputStreamReader(fis);
        BufferedReader br = new BufferedReader(ris);
        try { // FIXME: no better way?
            while(true) {
                // Read a single NodePeer
                SimpleFieldSet fs;
                fs = new SimpleFieldSet(br);
                PeerNode pn;
                try {
                    pn = new PeerNode(fs, node, true);
                } catch (FSParseException e2) {
                    Logger.error(this, "Could not parse peer: "+e2+"\n"+fs.toString(),e2);
                    continue;
                } catch (PeerParseException e2) {
                    Logger.error(this, "Could not parse peer: "+e2+"\n"+fs.toString(),e2);
                    continue;
                }
                addPeer(pn);
                gotSome = true;
            }
        } catch (EOFException e) {
            // End of file, fine
        } catch (IOException e1) {
            Logger.error(this, "Could not read peers file: "+e1, e1);
        } finally {
            try {
                br.close();
            } catch (IOException e3) {
                Logger.error(this, "Ignoring "+e3+" caught reading "+filename, e3);
            }
            return gotSome;
        }
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
        node.removePeerNodeStatus( peerNodeStatus, pn );
        String peerNodePreviousRoutingBackoffReason = pn.getPreviousBackoffReason();
        if(peerNodePreviousRoutingBackoffReason != null) {
        	node.removePeerNodeRoutingBackoffReason(peerNodePreviousRoutingBackoffReason, pn);
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
    	if(!pn.isRoutable()) {
    		Logger.minor(this, "Not ReallyConnected: "+pn);
    		return;
    	}
    	synchronized(this) {
        for(int i=0;i<connectedPeers.length;i++) {
            if(connectedPeers[i] == pn) {
                Logger.minor(this, "Already connected: "+pn);
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
        Logger.minor(this, "Connecting: "+pn);
        PeerNode[] newConnectedPeers = new PeerNode[connectedPeers.length+1];
        System.arraycopy(connectedPeers, 0, newConnectedPeers, 0, connectedPeers.length);
        newConnectedPeers[connectedPeers.length] = pn;
        connectedPeers = newConnectedPeers;
        Logger.minor(this, "Connected peers: "+connectedPeers.length);
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
    public void connect(SimpleFieldSet noderef) throws FSParseException, PeerParseException {
        PeerNode pn = new PeerNode(noderef, node, false);
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
        for(int i=0;i<myPeers.length;i++) {
            PeerNode pn = myPeers[i];
            if(pn == exclude) continue;
            if(pn.isRoutable()) {
                v.add(pn);
            } else {
            	Logger.minor(this, "Excluding "+pn+" because is disconnected");
            }
        }
        int lengthWithoutExcluded = v.size();
        if((exclude != null) && exclude.isRoutable())
            v.add(exclude);
        PeerNode[] newConnectedPeers = new PeerNode[v.size()];
        newConnectedPeers = (PeerNode[]) v.toArray(newConnectedPeers);
        Logger.minor(this, "Connected peers (in getRandomPeer): "+newConnectedPeers.length+" was "+connectedPeers.length);
        connectedPeers = newConnectedPeers;
        if(lengthWithoutExcluded == 0) return null;
        return connectedPeers[node.random.nextInt(lengthWithoutExcluded)];
    }

    /**
     * Asynchronously send this message to every connected peer.
     */
    public void localBroadcast(Message msg) {
        PeerNode[] peers;
        synchronized (this) {
			peers = connectedPeers;
		}
        for(int i=0;i<peers.length;i++) {
            if(peers[i].isRoutable()) try {
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
        for(int i=0;i<peers.length;i++) {
            PeerNode p = peers[i];
            if(!p.isRoutable()) continue;
            double peerloc = p.getLocation().getValue();
            if(Math.abs(peerloc - ignoreLoc) < Double.MIN_VALUE*2)
            	continue;
            double diff = distance(peerloc, loc);
            if(diff < bestDiff) {
                bestDiff = diff;
                bestLoc = peerloc;
            }
        }
        return bestLoc;
    }

    public boolean isCloserLocation(double loc) {
    	double nodeLoc = node.lm.getLocation().getValue();
    	double nodeDist = distance(nodeLoc, loc);
    	double closest = closestPeerLocation(loc, nodeLoc);
    	double closestDist = distance(closest, loc);
    	return closestDist < nodeDist;
    }
    
    /**
     * Find the peer which is closest to the target location
     */
    public PeerNode closestPeer(double loc) {
        PeerNode[] peers;
        synchronized (this) {
        	peers = connectedPeers;
		}
        double bestDiff = 1.0;
        PeerNode best = null;
        for(int i=0;i<peers.length;i++) {
            PeerNode p = peers[i];
            if(!p.isRoutable()) continue;
            double diff = distance(p, loc);
            if(diff < bestDiff) {
                best = p;
                bestDiff = diff;
            }
        }
        return best;
    }
    
    static double distance(PeerNode p, double loc) {
    	double d = distance(p.getLocation().getValue(), loc);
    	return d;
    	//return d * p.getBias();
    }
    
    /**
     * Distance between two locations.
     */
    public static double distance(double a, double b) {
        // Circular keyspace
    	if (a > b) return Math.min (a - b, 1.0 - a + b);
    	else return Math.min (b - a, 1.0 - b + a);
    }

    /**
     * FIXME
     * This scans the same array 4 times.  It would be better to scan once and execute 4 callbacks...
     * For this reason the metrics are only updated if advanced mode is enabled
     */
    public PeerNode closerPeer(PeerNode pn, HashSet routedTo, HashSet notIgnored, double loc, boolean ignoreSelf) {
	PeerNode best = _closerPeer(pn, routedTo, notIgnored, loc, ignoreSelf, false);
	if ((best != null) && (node.getToadletContainer() != null) &&
			node.getToadletContainer().isAdvancedDarknetEnabled()) {
		PeerNode nbo = _closerPeer(pn, routedTo, notIgnored, loc, ignoreSelf, true);
		if(nbo != null) {
			node.missRoutingDistance.report(distance(best, nbo.getLocation().getValue()));
			int numberOfConnected = node.getPeerNodeStatusSize(Node.PEER_NODE_STATUS_CONNECTED);
			int numberOfRoutingBackedOff = node.getPeerNodeStatusSize(Node.PEER_NODE_STATUS_ROUTING_BACKED_OFF);
			if (numberOfRoutingBackedOff + numberOfConnected > 0 ) {
				node.backedoffPercent.report((double) numberOfRoutingBackedOff / (double) (numberOfRoutingBackedOff + numberOfConnected));
			}
		}
	}
	return best;
    }
	    
    /**
     * Find the peer, if any, which is closer to the target location
     * than we are, and is not included in the provided set.
     */
    private PeerNode _closerPeer(PeerNode pn, HashSet routedTo, HashSet notIgnored, double loc, boolean ignoreSelf, boolean ignoreBackedOff) {
        PeerNode[] peers;  
        synchronized (this) {
			peers = connectedPeers;
		}
        Logger.minor(this, "Choosing closest peer: connectedPeers="+peers.length);
        double bestDiff = Double.MAX_VALUE;
        double maxDiff = 0.0;
        if(!ignoreSelf)
            maxDiff = distance(node.lm.getLocation().getValue(), loc);
        PeerNode best = null;
        PeerNode any = null;
        int count = 0;
        for(int i=0;i<peers.length;i++) {
            PeerNode p = peers[i];
            if(routedTo.contains(p)) {
            	Logger.minor(this, "Skipping (already routed to): "+p.getPeer());
            	continue;
            }
            if(p == pn) {
            	Logger.minor(this, "Skipping (req came from): "+p.getPeer());
            	continue;
            }
            if(!p.isRoutable()) {
            	Logger.minor(this, "Skipping (not connected): "+p.getPeer());
            	continue;
            }
            if((!ignoreBackedOff) && p.isRoutingBackedOff()) {
            	Logger.minor(this, "Skipping (routing backed off): "+p.getPeer());
            	continue;
            }
            count++;
            any = p;
            double diff = distance(p, loc);
            Logger.minor(this, "p.loc="+p.getLocation().getValue()+", loc="+loc+", d="+distance(p.getLocation().getValue(), loc)+" usedD="+diff+" for "+p.getPeer());
            if((!ignoreSelf) && (diff > maxDiff)) continue;
            if(diff < bestDiff) {
                best = p;
                bestDiff = diff;
            }
        }
        if(count == 1) return any;
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
            status[i] = pn.getStatus();
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
    
    /**
     * Write the peers file to disk
     */
    void writePeers() {
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
            OutputStreamWriter w = new OutputStreamWriter(fos);
            try {
            	boolean succeeded = writePeers(w);
                w.close();
                if(!succeeded) return;
            } catch (IOException e) {
            	try {
            		w.close();
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

	public boolean writePeers(OutputStreamWriter w) {
        PeerNode[] peers;
        synchronized (this) {
			peers = myPeers;
		}
        for (int i = 0; i < peers.length; i++) {
            try {
                peers[i].write(w);
                w.flush();
            } catch (IOException e) {
                try {
                    w.close();
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
		int conns, peers;
		synchronized(this) {
			conns = this.connectedPeers.length;
			peers = this.myPeers.length;
		}
		synchronized(ua) {
			ua.conns = conns;
			ua.peers = peers;
			ua.neverConn = node.getPeerNodeStatusSize(Node.PEER_NODE_STATUS_NEVER_CONNECTED);
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

}
