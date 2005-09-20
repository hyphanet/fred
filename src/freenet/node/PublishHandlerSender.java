package freenet.node;

import java.util.HashSet;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.keys.PublishStreamKey;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.ShortBuffer;

/**
 * Forwards a PublishData message. Routed, exactly as an insert.
 * Also communicates with the previous hop. For inserts and requests
 * we split this into two - Handler and Sender - but for this it is
 * simpler to have one, especially as there is no coalescing.
 */
public class PublishHandlerSender implements Runnable {

    // Basics
    final PublishStreamKey key;
    final double target;
    final long uid;
    private short htl;
    final PeerNode source;
    final Node node;
    final long packetNumber;
    final byte[] packetData;
    final Thread t;
    private double closestLocation;
    
    // Constants - FIXME way too high?
    static final int ACCEPTED_TIMEOUT = 10000;
    static final int COMPLETE_TIMEOUT = 120000;

    public String toString() {
        return super.toString()+": id="+uid+", source="+source+", packetNo="+packetNumber+", data="+Fields.hashCode(packetData);
    }
    
    
    /**
     * Create a PublishHandlerSender.
     * closestLocation must be reset by the caller if we are closer to target
     * than the node sending the request.
     */
    public PublishHandlerSender(PublishStreamKey key2, long id, long packetNo, byte[] data, Node n, PeerNode src, double closestLoc) {
        node = n;
        key = key2;
        target = key.toNormalizedDouble();
        uid = id;
        packetNumber = packetNo;
        packetData = data;
        source = src;
        closestLocation = closestLoc;
        htl = Node.MAX_HTL;
        t = new Thread(this);
        t.setDaemon(true);
        t.start();
    }
    
    /**
     * Create from an incoming Message. Does not check
     * UID/key for collision; this must be done by caller.
     * Will do everything else though, including resetting
     * the closestLocation if necessary.
     */
    public PublishHandlerSender(Message m, Node n) {
        this.node = n;
        key = (PublishStreamKey) m.getObject(DMT.KEY);
        target = key.toNormalizedDouble();
        uid = m.getLong(DMT.UID);
        packetNumber = m.getLong(DMT.STREAM_SEQNO);
        source = (PeerNode) m.getSource();
        packetData = ((ShortBuffer)m.getObject(DMT.DATA)).getData();
        closestLocation = m.getDouble(DMT.NEAREST_LOCATION);
        htl = m.getShort(DMT.HTL);
        double myLoc = n.lm.getLocation().getValue();
        if(Math.abs(myLoc - target) < Math.abs(closestLocation - target))
            closestLocation = myLoc;
        t = new Thread(this);
        t.setDaemon(true);
        t.start();
    }

    /** Very similar to InsertSender.run() */
    public void run() {
        Logger.minor(this, "Running "+this);
        try {
            // Copy it as it will be decrypted in place
            byte[] packetDataCopy = new byte[packetData.length];
            System.arraycopy(packetData, 0, packetDataCopy, 0, packetData.length);
            node.subscriptions.receivedPacket(key, packetNumber, packetDataCopy, source);
            short origHTL = htl;
            if(source != null) {
                Message msg = DMT.createFNPAccepted(uid);
                try {
                    source.sendAsync(msg, null);
                } catch (NotConnectedException e) {
                    Logger.normal(this, "Not connected sending Accepted on "+this);
                }
            }
            
            HashSet nodesRoutedTo = new HashSet();
            
            // Don't check whether source is connected; forward anyway
            
            while(true) {
                if(htl == 0) {
                    // Ran out of hops; success
                    if(source != null) {
                        Message msg = DMT.createFNPPublishDataSucceeded(uid);
                        try {
                            source.sendAsync(msg, null);
                        } catch (NotConnectedException e) {
                            Logger.minor(this, "Not connected sending FNPPublishDataSucceeded on "+this);
                        }
                    }
                    return;
                }
                
                // Route
                PeerNode next;
                
                // Can backtrack, so only route to nodes closer than we are to target.
                double nextValue;
                synchronized(node.peers) {
                    next = node.peers.closerPeer(source, nodesRoutedTo, target, true);
                    if(next != null)
                        nextValue = next.getLocation().getValue();
                    else
                        nextValue = -1.0;
                }
                
                if(next == null) {
                    // Backtrack
                    Logger.minor(this, "No more routes");
                    if(source != null) {
                        Message msg = DMT.createFNPRouteNotFound(uid, htl);
                        try {
                            source.sendAsync(msg, null);
                        } catch (NotConnectedException e) {
                            Logger.normal(this, "Not connected when RNFing on "+this);
                        }
                    } else {
                        Logger.minor(this, "RNF on "+this);
                    }
                    return;
                }
                Logger.minor(this, "Routing insert to "+next);
                nodesRoutedTo.add(next);
                
                if(Math.abs(target - nextValue) > Math.abs(target - closestLocation)) {
                    Logger.minor(this, "Backtracking: target="+target+" next="+nextValue+" closest="+closestLocation);
                    htl = node.decrementHTL(source, htl);
                }
                
                // Now we send it
                /**
                 * Possible responses:
                 * Accepted
                 * RejectedOverload
                 * RejectedLoop
                 * (timeout)
                 */
                Message msg = DMT.createFNPPublishData(htl, packetData, key, packetNumber, uid, closestLocation);
                
                MessageFilter mfAccepted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
                MessageFilter mfRejectedLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedLoop);
                MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedOverload);
                MessageFilter mfPublishDataInvalid = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPPublishDataInvalid);
                
                // mfRejectedOverload must be the last thing in the or
                // So its or pointer remains null
                // Otherwise we need to recreate it below
                MessageFilter mf = mfAccepted.or(mfRejectedLoop.or(mfRejectedOverload.or(mfPublishDataInvalid)));
                mfRejectedOverload.clearOr();
                
                try {
                    next.send(msg);
                } catch (NotConnectedException e) {
                    Logger.minor(this, "Not connected to "+next+" - skipping");
                    continue;
                }
                
                try {
                    msg = null;
                    msg = node.usm.waitFor(mf);
                } catch (DisconnectedException e1) {
                    Logger.minor(this, "Not connected to "+next+" while waiting, skipping");
                    continue;
                }
                
                if(msg == null) {
                    // Timeout waiting for Accepted
                    Logger.normal(this, "Timed out waiting for Accepted on "+this+" from "+next);
                    continue;
                }
                
                if(msg.getSpec() == DMT.FNPRejectedLoop) {
                    Logger.minor(this, "Rejected: loop for "+this);
                    continue;
                }
                
                if(msg.getSpec() == DMT.FNPRejectedOverload) {
                    // Propagate back to source; fatal
                    if(source != null) {
                        Message m = DMT.createFNPRejectedOverload(uid);
                        try {
                            source.sendAsync(m,null);
                        } catch (NotConnectedException e2) {
                            Logger.normal(this, "Source disconnected relaying rejected:overload from "+next+" for "+this);
                        }
                    } else {
                        Logger.normal(this, "FNPDataPublish rejected: overload from "+next+" for "+this);
                    }
                    return;
                }

                if(msg.getSpec() != DMT.FNPAccepted) {
                    throw new IllegalStateException("Unrecognized message: "+msg+" while waiting for Accepted on "+this);
                }
                
                // Got an Accepted; wait for a success
                
                MessageFilter mfSucceeded = MessageFilter.create().setTimeout(COMPLETE_TIMEOUT).setSource(next).setField(DMT.UID, uid).setType(DMT.FNPPublishDataSucceeded);
                MessageFilter mfRNF = MessageFilter.create().setTimeout(COMPLETE_TIMEOUT).setSource(next).setField(DMT.UID, uid).setType(DMT.FNPRouteNotFound);
                mfRejectedOverload.clearOr();
                mfPublishDataInvalid.clearOr();
                
                mf = mfSucceeded.or(mfRNF.or(mfRejectedOverload.or(mfPublishDataInvalid)));
                
                msg = null;
                try {
                    msg = node.usm.waitFor(mf);
                } catch (DisconnectedException e3) {
                    Logger.error(this, "Disconnected from next: "+next+" while waiting for completion on "+this);
                    continue; // can cause load multiplication, hence the error; what else are we supposed to do though?
                }
                
                if(msg == null || msg.getSpec() == DMT.FNPRejectedOverload) {
                    // Timeout
                    Logger.error(this, "Timeout waiting for completion of "+this+" : "+msg);
                    // Propagate back to source; fatal
                    if(source != null) {
                        Message m = DMT.createFNPRejectedOverload(uid);
                        try {
                            source.sendAsync(m,null);
                        } catch (NotConnectedException e2) {
                            Logger.normal(this, "Source disconnected relaying rejected:overload from "+next+" for "+this);
                        }
                    } else {
                        Logger.normal(this, "FNPDataPublish rejected: overload from "+next+" for "+this);
                    }
                    return;
                }
                
                if(msg.getSpec() == DMT.FNPRouteNotFound) {
                    Logger.minor(this, "Rejected: RNF");
                    // Still gets the data - but not yet
                    short newHtl = msg.getShort(DMT.HTL);
                    if(htl > newHtl) htl = newHtl;
                    continue;

                }
                
                if(msg.getSpec() == DMT.FNPPublishDataInvalid) {
                    Logger.error(this, "Got data invalid from "+next+" for "+this);
                    // FIXME: check validity ourself, propagate error if needed
                    continue;
                }
                
                if(msg.getSpec() != DMT.FNPPublishDataSucceeded) {
                    throw new IllegalStateException("Got unexpected message "+msg+" waiting for completion on "+this);
                }
                
                // Success
                return;
            }
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t+" in "+this, t);
        }
    }

}
