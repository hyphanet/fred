package freenet.node;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Vector;

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
    PeerNode[] myPeers;
    
    /** All the peers we are actually connected to */
    PeerNode[] connectedPeers;
    
    /**
     * Create a PeerManager by reading a list of peers from
     * a file.
     * @param node
     * @param filename
     */
    public PeerManager(Node node, String filename) {
        myPeers = new PeerNode[0];
        connectedPeers = new PeerNode[0];
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
                    PeerNode pn;
                    try {
                        pn = new PeerNode(fs, node);
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
    private synchronized void addPeer(PeerNode pn) {
        for(int i=0;i<myPeers.length;i++) {
            if(myPeers[i] == pn) return;
        }
        PeerNode[] newMyPeers = new PeerNode[myPeers.length+1];
        System.arraycopy(myPeers, 0, newMyPeers, 0, myPeers.length);
        newMyPeers[myPeers.length] = pn;
        myPeers = newMyPeers;
        Logger.normal(this, "Added "+pn);
    }

    public synchronized void addConnectedPeer(PeerNode pn) {
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
            if(myPeers[i].getPeer().equals(peer))
                return myPeers[i];
        }
        return null;
    }

    /**
     * Connect to a node provided the fieldset representing it.
     */
    public void connect(SimpleFieldSet noderef) throws FSParseException, PeerParseException {
        PeerNode pn = new PeerNode(noderef, node);
        for(int i=0;i<myPeers.length;i++) {
            if(Arrays.equals(myPeers[i].identity, pn.identity)) return;
        }
        addPeer(pn);
    }

    /**
     * @return An array of the current locations (as doubles) of all
     * our connected peers.
     */
    public double[] getPeerLocationDoubles() {
        double[] locs;
        PeerNode[] conns = connectedPeers;
        locs = new double[connectedPeers.length];
        int x = 0;
        for(int i=0;i<conns.length;i++) {
            if(conns[i].isConnected())
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
            if(pn.isConnected()) return pn;
        }
        // None of them worked
        // Move the un-connected ones out
        // This is safe as they will add themselves when they
        // reconnect, and they can't do it yet as we are synchronized.
        Vector v = new Vector(connectedPeers.length);
        for(int i=0;i<connectedPeers.length;i++) {
            PeerNode pn = connectedPeers[i];
            if(pn == exclude) continue;
            if(pn.isConnected()) {
                v.add(pn);
            }
        }
        int lengthWithoutExcluded = v.size();
        if(exclude != null && exclude.isConnected())
            v.add(exclude);
        PeerNode[] newConnectedPeers = new PeerNode[v.size()];
        newConnectedPeers = (PeerNode[]) v.toArray(newConnectedPeers);
        connectedPeers = newConnectedPeers;
        if(lengthWithoutExcluded == 0) return null;
        return connectedPeers[node.random.nextInt(lengthWithoutExcluded)];
    }

    /**
     * Asynchronously send this message to every connected peer.
     */
    public void localBroadcast(Message msg) {
        PeerNode[] peers = connectedPeers; // avoid synchronization
        for(int i=0;i<peers.length;i++) {
            if(peers[i].isConnected())
                peers[i].sendAsync(msg);
        }
    }

    public PeerNode getRandomPeer() {
        return getRandomPeer(null);
    }

    /**
     * Find the peer which is closest to the target location
     */
    public PeerNode closestPeer(double loc) {
        PeerNode[] peers = connectedPeers;
        double bestDiff = 1.0;
        PeerNode best = null;
        for(int i=0;i<peers.length;i++) {
            PeerNode p = peers[i];
            if(!p.isConnected()) continue;
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
    public PeerNode closerPeer(PeerNode pn, HashSet routedTo, double loc, boolean ignoreSelf) {
        PeerNode[] peers = connectedPeers;
        double bestDiff = 1.0;
        double minDiff = 0.0;
        if(!ignoreSelf)
            minDiff = distance(node.lm.getLocation().getValue(), loc);
        PeerNode best = null;
        PeerNode any = null;
        int count = 0;
        for(int i=0;i<peers.length;i++) {
            PeerNode p = peers[i];
            if(routedTo.contains(p)) continue;
            if(p == pn) continue;
            if(!p.isConnected()) continue;
            count++;
            any = p;
            double diff = distance(p.getLocation().getValue(), loc);
            if((!ignoreSelf) && diff > minDiff) continue;
            if(diff < bestDiff) {
                best = p;
                bestDiff = diff;
            }
        }
        if(count == 1) return any;
        return best;
    }
}
