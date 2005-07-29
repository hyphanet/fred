/*
 * Created on Jul 28, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet.node;

import java.util.HashSet;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.keys.NodeCHK;
import freenet.support.Logger;

public class InsertSender implements Runnable {

    InsertSender(NodeCHK myKey, long uid, byte[] headers, short htl, NodePeer source, Node node) {
        this.myKey = myKey;
        this.target = myKey.toNormalizedDouble();
        this.uid = uid;
        this.headers = headers;
        this.htl = htl;
        this.source = source;
        this.node = node;
    }
    
    // Constants
    static final int ACCEPTED_TIMEOUT = 5000;
    static final int PUT_TIMEOUT = 120000;

    // Basics
    final NodeCHK myKey;
    final double target;
    final long uid;
    short htl;
    final NodePeer source;
    final Node node;
    final byte[] headers; // received BEFORE creation => we handle Accepted elsewhere
    
    private int status = -1;
    static final int NOT_FINISHED = -1;
    static final int SUCCESS = 0;
    static final int ROUTE_NOT_FOUND = 1;
    static final int REJECTED_OVERLOAD = 2;
    static final int TRANSFER_FAILED = 3;
    static final int VERIFY_FAILURE = 4;
    
    public void run() {
        
        Message req = DMT.createFNPInsertRequest(uid, myKey, htl);
        
        HashSet nodesRoutedTo = new HashSet();
        
        while(true) {
            if(htl == 0) {
                // FIXME
            }
            
            // Route it
            NodePeer next;
            // Can backtrack, so only route to nodes closer than we are to target.
            next = node.peers.closerPeer(source, nodesRoutedTo, target, source == null);
            
            if(next == null) {
                // Backtrack
                finish(ROUTE_NOT_FOUND);
                return;
            }
            nodesRoutedTo.add(next);
            
            // Wait for ack or reject
            
            MessageFilter mfAccepted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
            MessageFilter mfRejectedLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectLoop);
            MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectOverload);
            
            // mfRejectedOverload must be the last thing in the or
            // So its or pointer remains null
            // Otherwise we need to recreate it below
            MessageFilter mf = mfAccepted.or(mfRejectedLoop.or(mfRejectedOverload));
            
            // Send to next node
            
            next.send(req);
            
            Message msg = node.usm.waitFor(mf);
            
            if(msg == null) {
                // No response, move on
                htl = node.decrementHTL(source, htl);
                continue;
            }
            
            if(msg.getSpec() == DMT.FNPRejectLoop) {
                // Loop - we don't want to send the data to this one
                htl = node.decrementHTL(source, htl);
                continue;
            }
            
            if(msg.getSpec() == DMT.FNPRejectOverload) {
                // Overload... hmmmm - propagate error back to source
                finish(REJECTED_OVERLOAD);
                return;
            }
            
            // Otherwise must be an Accepted
            
            // Send them the data
            
            /** What are we waiting for now??:
             * - FNPRouteNotFound - couldn't exhaust HTL, but send us the 
             *   data anyway please
             * - FNPInsertReply - used up all HTL, yay
             * - FNPRejectOverload - propagating an overload error :(
             */
            
            MessageFilter mfRNF = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPRouteNotFound);
            MessageFilter mfInsertReply = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPInsertReply);
            
            mf = mfRNF.or(mfInsertReply.or(mfRejectedOverload.setTimeout(PUT_TIMEOUT)));
            
            Message dataInsert = DMT.createFNPDataInsert(uid, headers);
            
            next.send(dataInsert);
            
            msg = node.usm.waitFor(mf);
            
            if(msg == null) {
                // Timeout :(
                // Fairly serious problem
                Logger.error(this, "Timeout after Accepted in insert");
                // Treat as rejected-overload
                finish(REJECTED_OVERLOAD);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPRejectOverload) {
                finish(REJECTED_OVERLOAD);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPRouteNotFound) {
                short newHtl = msg.getShort(DMT.HTL);
                htl = node.decrementHTL(source, htl);
                if(htl > newHtl) htl = newHtl;
                continue;
            }

            // Otherwise should be an InsertReply
            // Our task is complete
            finish(SUCCESS);
        }
    }

    /**
     * Wait until we have a terminal status code.
     */
    public void waitUntilFinished() {
        synchronized(this) {
            while(true) {
                if(status != NOT_FINISHED) return;
                try {
                    wait(10000);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }            
        }
    }
    
    private void finish(int code) {
        status = code;
        synchronized(this) {
            notifyAll();
        }
    }
}
