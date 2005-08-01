package freenet.node;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;

import freenet.io.comm.Message;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
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
    NodePeer[] myPeers;
    
    /** All the peers we are actually connected to */
    NodePeer[] connectedPeers;
    
    /**
     * @param node
     * @param string
     */
    public PeerManager(Node node, String filename) {
        myPeers = new NodePeer[0];
        connectedPeers = new NodePeer[0];
        this.node = node;
        try {
            // Try to read the node list from disk
            FileInputStream fis = new FileInputStream(filename);
            InputStreamReader ris = new InputStreamReader(fis);
            BufferedReader br = new BufferedReader(ris);
            try { // FIXME: no better way?
                while(true) {
                    // Read a single NodePeer
                    SimpleFieldSet fs;
                    fs = new SimpleFieldSet(br);
                    NodePeer pn;
                    try {
                        pn = new NodePeer(fs, node);
                    } catch (FSParseException e2) {
                        Logger.error(this, "Could not parse peer: "+e2+"\n"+fs.toString(),e2);
                        continue;
                    } catch (PeerParseException e2) {
                        Logger.error(this, "Could not parse peer: "+e2+"\n"+fs.toString(),e2);
                        continue;
                    }
                    addPeer(pn);
                }
            } catch (EOFException e) {
                // End of file, fine
            } catch (IOException e1) {
                Logger.error(this, "Could not read peers file: "+e1, e1);
                return;
            } finally {
                try {
                    br.close();
                } catch (IOException e3) {
                    // Ignore
                }
            }
        } catch (FileNotFoundException e) {
            Logger.error(this, "Peers file not found: "+filename);
        }
    }

    /**
     * @param pn
     */
    private synchronized void addPeer(NodePeer pn) {
        for(int i=0;i<myPeers.length;i++) {
            if(myPeers[i] == pn) return;
        }
        // Add it to both, since we have no connect/disconnect protocol
        // FIXME!
        NodePeer[] newMyPeers = new NodePeer[myPeers.length+1];
        System.arraycopy(myPeers, 0, newMyPeers, 0, myPeers.length);
        newMyPeers[myPeers.length] = pn;
        NodePeer[] newConnectedPeers = new NodePeer[connectedPeers.length+1];
        System.arraycopy(connectedPeers, 0, newConnectedPeers, 0, connectedPeers.length);
        newConnectedPeers[connectedPeers.length] = pn;
        myPeers = newMyPeers;
        connectedPeers = newConnectedPeers;
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
    public NodePeer getByPeer(Peer peer) {
        for(int i=0;i<myPeers.length;i++) {
            if(myPeers[i].getPeer().equals(peer))
                return myPeers[i];
        }
        return null;
    }

    /**
     * Connect to a node provided the fieldset representing it.
     */
    public void connect(SimpleFieldSet noderef) throws FSParseException, PeerParseException {
        NodePeer pn = new NodePeer(noderef, node);
        for(int i=0;i<myPeers.length;i++) {
            if(Arrays.equals(myPeers[i].getNodeIdentity(), pn.getNodeIdentity())) return;
        }
        addPeer(pn);
    }

    /**
     * @return An array of the current locations (as doubles) of all
     * our connected peers.
     */
    public double[] getPeerLocationDoubles() {
        double[] locs;
        NodePeer[] conns = connectedPeers;
        locs = new double[connectedPeers.length];
        for(int i=0;i<conns.length;i++)
            locs[i] = conns[i].getLocation().getValue();
        // Wipe out any information contained in the order
        java.util.Arrays.sort(locs);
        return locs;
    }

    /**
     * @return A random connected peer.
     * FIXME: should this take performance into account?
     */
    public synchronized NodePeer getRandomPeer() {
        if(connectedPeers.length == 0) return null;
        return connectedPeers[node.random.nextInt(connectedPeers.length)];
    }

    /**
     * Asynchronously send this message to every connected peer.
     */
    public void localBroadcast(Message msg) {
        NodePeer[] peers = connectedPeers; // avoid synchronization
        for(int i=0;i<peers.length;i++) {
            peers[i].sendAsync(msg);
        }
    }

    public NodePeer getRandomPeer(NodePeer pn) {
        NodePeer[] peers = connectedPeers;
        if(peers.length < 2) return null;
        while(true) {
            NodePeer p = connectedPeers[node.random.nextInt(connectedPeers.length)];
            if(p == pn) continue;
            return p;
        }
    }

    /**
     * Find the peer which is closest to the target location
     */
    public NodePeer closestPeer(double loc) {
        NodePeer[] peers = connectedPeers;
        double bestDiff = 1.0;
        NodePeer best = null;
        for(int i=0;i<peers.length;i++) {
            NodePeer p = peers[i];
            double diff = distance(p.getLocation().getValue(), loc);
            if(diff < bestDiff) {
                best = p;
                bestDiff = diff;
            }
        }
        return best;
    }

    /**
     * Distance between two locations.
     */
    static double distance(double d, double loc) {
        // Circular keyspace
        double dist = Math.abs(d-loc);
        double min = Math.min(d, loc);
        double max = Math.max(d, loc);
        double altdist = Math.abs(1.0+min-max);
        return Math.min(dist, altdist);
    }

    /**
     * Find the peer, if any, which is closer to the target location
     * than we are, and is not included in the provided set.
     */
    public NodePeer closerPeer(NodePeer pn, HashSet routedTo, double loc, boolean ignoreSelf) {
        NodePeer[] peers = connectedPeers;
        double bestDiff = 1.0;
        double minDiff = 0.0;
        if(!ignoreSelf)
            minDiff = distance(node.lm.getLocation().getValue(), loc);
        NodePeer best = null;
        for(int i=0;i<peers.length;i++) {
            NodePeer p = peers[i];
            if(routedTo.contains(p)) continue;
            if(p == pn) continue;
            double diff = distance(p.getLocation().getValue(), loc);
            if((!ignoreSelf) && diff > minDiff) continue;
            if(diff < bestDiff) {
                best = p;
                bestDiff = diff;
            }
        }
        return best;
    }
}
