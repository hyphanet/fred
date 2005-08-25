package freenet.node;

import java.util.HashSet;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.RetrievalException;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.NodeCHK;
import freenet.support.Logger;
import freenet.support.ShortBuffer;

/**
 * @author amphibian
 * 
 * Sends a request out onto the network, and deals with the 
 * consequences. Other half of the request functionality is provided
 * by RequestHandler.
 * 
 * Must put self onto node's list of senders on creation, and remove
 * self from it on destruction. Must put self onto node's list of
 * transferring senders when starts transferring, and remove from it
 * when finishes transferring.
 */
public final class RequestSender implements Runnable {

    // Constants
    static final int ACCEPTED_TIMEOUT = 5000;
    static final int FETCH_TIMEOUT = 60000;
    
    // Basics
    final NodeCHK key;
    final double target;
    private short htl;
    final long uid;
    final Node node;
    /** The source of this request if any - purely so we can avoid routing to it */
    final PeerNode source;
    private PartiallyReceivedBlock prb = null;
    private byte[] headers;
    
    // Terminal status
    // Always set finished AFTER setting the reason flag
    private boolean finished = false;
    
    private int status = -1;
    static final int NOT_FINISHED = -1;
    static final int SUCCESS = 0;
    static final int ROUTE_NOT_FOUND = 1;
    static final int REJECTED_OVERLOAD = 2;
    static final int DATA_NOT_FOUND = 3;
    static final int TRANSFER_FAILED = 4;
    static final int VERIFY_FAILURE = 5;
    
    private boolean transferring = false;
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public RequestSender(NodeCHK key, short htl, long uid, Node n, 
            PeerNode source) {
        this.key = key;
        this.htl = htl;
        this.uid = uid;
        this.node = n;
        this.source = source;
        target = key.toNormalizedDouble();
        Thread t = new Thread(this, "RequestSender for UID "+uid);
        t.setDaemon(true);
        t.start();
    }
    
    public void run() {
        short origHTL = htl;
        Message req = DMT.createFNPDataRequest(uid, htl, key);
        node.addSender(key, htl, this);
        HashSet nodesRoutedTo = new HashSet();
        try {
        while(true) {
            Logger.minor(this, "htl="+htl);
            if(htl == 0) {
                // RNF
                // Would be DNF if arrived with no HTL
                // But here we've already routed it and that's been rejected.
                finish(ROUTE_NOT_FOUND);
                return;
            }
            // Route it
            PeerNode next;
            // Can backtrack, so only route to nodes closer than we are to target.
            next = node.peers.closerPeer(source, nodesRoutedTo, target, source == null);
            
            if(next == null) {
                // RNF
                finish(ROUTE_NOT_FOUND);
                return;
            }
            nodesRoutedTo.add(next);
            
            /**
             * What are we waiting for?
             * FNPAccepted - continue
             * FNPRejectedLoop - go to another node
             * FNPRejectedOverload - fail (propagates back to source,
             * then reduces source transmit rate)
             */
            
            MessageFilter mfAccepted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
            MessageFilter mfRejectedLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedLoop);
            MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedOverload);

            // mfRejectedOverload must be the last thing in the or
            // So its or pointer remains null
            // Otherwise we need to recreate it below
            MessageFilter mf = mfAccepted.or(mfRejectedLoop.or(mfRejectedOverload));
            
            next.send(req);
            
            Message msg;
            try {
                msg = node.usm.waitFor(mf);
            } catch (DisconnectedException e) {
                Logger.normal(this, "Disconnected from "+next+" while waiting for Accepted on "+uid);
                continue;
            }
            
            if(msg == null) {
                // Timeout
                // Treat as FNPRejectOverloadd
                finish(REJECTED_OVERLOAD);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPRejectedLoop) {
                htl = node.decrementHTL(source, htl);
                // Find another node to route to
                continue;
            }
            
            if(msg.getSpec() == DMT.FNPRejectedOverload) {
                // Failed. Propagate back to source.
                // Source will reduce send rate.
                finish(REJECTED_OVERLOAD);
                return;
            }
            
            // Otherwise, must be Accepted
            
            // So wait...
            
            MessageFilter mfDNF = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPDataNotFound);
            MessageFilter mfDF = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPDataFound);
            MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPRouteNotFound);
            mfRejectedOverload = mfRejectedOverload.setTimeout(FETCH_TIMEOUT);
            mf = mfDNF.or(mfDF.or(mfRouteNotFound.or(mfRejectedOverload)));

            try {
                msg = node.usm.waitFor(mf);
            } catch (DisconnectedException e) {
                Logger.normal(this, "Disconnected from "+next+" while waiting for data on "+uid);
                continue;
            }
            
            if(msg == null) {
                // Timeout. Treat as FNPRejectOverload.
                finish(REJECTED_OVERLOAD);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPDataNotFound) {
                finish(DATA_NOT_FOUND);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPRouteNotFound) {
                // Backtrack within available hops
                short newHtl = msg.getShort(DMT.HTL);
                htl = node.decrementHTL(source, htl);
                if(newHtl < htl) htl = newHtl;
                continue;
            }
            
            if(msg.getSpec() == DMT.FNPRejectedOverload) {
                finish(REJECTED_OVERLOAD);
                return;
            }

            // Found data
            
            // First get headers
            
            headers = ((ShortBuffer)msg.getObject(DMT.BLOCK_HEADERS)).getData();
            
            // FIXME: Validate headers

            transferring = true;
            node.addTransferringSender(key, this);
            try {
            
                prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
                
                synchronized(this) {
                    notifyAll();
                }
                
                BlockReceiver br = new BlockReceiver(node.usm, next, uid, prb);
                
                try {
                    byte[] data = br.receive();
                    // Received data
                    CHKBlock block;
                    try {
                        block = new CHKBlock(data, headers, key);
                    } catch (CHKVerifyException e1) {
                        Logger.normal(this, "Got data but verify failed: "+e1, e1);
                        finish(VERIFY_FAILURE);
                        return;
                    }
                    node.store(block);
                    finish(SUCCESS);
                    return;
                } catch (RetrievalException e) {
                    Logger.normal(this, "Transfer failed: "+e, e);
                    finish(TRANSFER_FAILED);
                    return;
                }
            } finally {
                transferring = false;
                node.removeTransferringSender(key, this);
            }
        }
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
        } finally {
            node.completed(uid);
            node.removeSender(key, origHTL, this);
        }
    }

    public PartiallyReceivedBlock getPRB() {
        return prb;
    }

    public boolean transferStarted() {
        return prb != null;
    }

    /**
     * Wait until either the transfer has started or we have a 
     * terminal status code.
     */
    public synchronized void waitUntilStatusChange() {
        while(true) {
            if(prb != null) return;
            if(status != NOT_FINISHED) return;
            try {
                wait(10000);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Wait until we have a terminal status code.
     */
    public synchronized void waitUntilFinished() {
        while(true) {
            if(status != NOT_FINISHED) return;
            try {
                wait(10000);
            } catch (InterruptedException e) {
                // Ignore
            }
        }            
    }
    
    private void finish(int code) {
        Logger.minor(this, "finish("+code+")");
        status = code;
        finished = true;
        synchronized(this) {
            notifyAll();
        }
    }

    public byte[] getHeaders() {
        return headers;
    }

    public int getStatus() {
        return status;
    }

    public short getHTL() {
        return htl;
    }
}
