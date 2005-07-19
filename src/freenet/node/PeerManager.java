package freenet.node;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;

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
                    try {
                        fs = new SimpleFieldSet(br);
                    } catch (IOException e1) {
                        if(e1 instanceof EOFException)
                            throw (EOFException)e1;
                        Logger.error(this, "Could not read peers file: "+e1, e1);
                        return;
                    }
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
            }
            try {
                br.close();
            } catch (IOException e1) {
                Logger.error(this, "Can't close: "+e1);
                // WTF?
            }
        } catch (FileNotFoundException e) {
            Logger.error(this, "Peers file not found: "+filename);
        }
        
        // TODO Auto-generated constructor stub
    }

    /**
     * @param pn
     */
    private synchronized void addPeer(NodePeer pn) {
        // Add it to both, since we have no connect/disconnect protocol
        // FIXME!
        NodePeer[] newMyPeers = new NodePeer[myPeers.length+1];
        System.arraycopy(myPeers, 0, newMyPeers, 0, myPeers.length);
        NodePeer[] newConnectedPeers = new NodePeer[connectedPeers.length+1];
        System.arraycopy(connectedPeers, 0, newConnectedPeers, 0, connectedPeers.length);
        myPeers = newMyPeers;
        connectedPeers = newConnectedPeers;
    }

    NodePeer route(double targetLocation, RoutingContext ctx) {
        double minDist = 1.1;
        NodePeer best = null;
        for(int i=0;i<connectedPeers.length;i++) {
            NodePeer p = connectedPeers[i];
            if(ctx.alreadyRoutedTo(p)) continue;
            double loc = p.getLocation().getValue();
            double dist = Math.abs(loc - targetLocation);
            if(dist < minDist) {
                minDist = dist;
                best = p;
            }
        }
        return best;
    }
    
    NodePeer route(Location target, RoutingContext ctx) {
        return route(target.getValue(), ctx);
    }

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
}
