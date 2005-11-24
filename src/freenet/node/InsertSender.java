package freenet.node;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.NodeCHK;
import freenet.support.Logger;

public final class InsertSender implements Runnable {

    public class Sender implements Runnable {

		public void run() {
			bt.send();
		}
	}

	InsertSender(NodeCHK myKey, long uid, byte[] headers, short htl, 
            PeerNode source, Node node, PartiallyReceivedBlock prb, boolean fromStore, double closestLocation) {
        this.myKey = myKey;
        this.target = myKey.toNormalizedDouble();
        this.uid = uid;
        this.headers = headers;
        this.htl = htl;
        this.source = source;
        this.node = node;
        this.prb = prb;
        this.fromStore = fromStore;
        this.closestLocation = closestLocation;
        this.startTime = System.currentTimeMillis();
        senderThreads = new LinkedList();
        Thread t = new Thread(this, "InsertSender for UID "+uid+" on "+node.portNumber+" at "+System.currentTimeMillis());
        t.setDaemon(true);
        t.start();
    }
    
    // Constants
    static final int ACCEPTED_TIMEOUT = 10000;
    static final int PUT_TIMEOUT = 120000;

    // Basics
    final NodeCHK myKey;
    final double target;
    final long uid;
    short htl;
    final PeerNode source;
    final Node node;
    final byte[] headers; // received BEFORE creation => we handle Accepted elsewhere
    final PartiallyReceivedBlock prb;
    final boolean fromStore;
    private boolean receiveFailed = false;
    final double closestLocation;
    final long startTime;
    private BlockTransmitter bt;
    private final LinkedList senderThreads;
    
    private int status = -1;
    static final int NOT_FINISHED = -1;
    static final int SUCCESS = 0;
    static final int ROUTE_NOT_FOUND = 1;
    static final int REJECTED_OVERLOAD = 2;
    static final int INTERNAL_ERROR = 3;
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public void run() {
        short origHTL = htl;
        try {
        HashSet nodesRoutedTo = new HashSet();
        
        while(true) {
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            
            if(htl == 0) {
                // Send an InsertReply back
                finish(SUCCESS, null);
                return;
            }
            
            // Route it
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
                finish(ROUTE_NOT_FOUND, null);
                return;
            }
            Logger.minor(this, "Routing insert to "+next);
            nodesRoutedTo.add(next);
            
            if(Math.abs(target - nextValue) > Math.abs(target - closestLocation)) {
                Logger.minor(this, "Backtracking: target="+target+" next="+nextValue+" closest="+closestLocation);
                htl = node.decrementHTL(source, htl);
            }
            
            Message req = DMT.createFNPInsertRequest(uid, htl, myKey, closestLocation);
            
            // Wait for ack or reject... will come before even a locally generated DataReply
            
            MessageFilter mfAccepted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
            MessageFilter mfRejectedLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedLoop);
            MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedOverload);
            
            // mfRejectedOverload must be the last thing in the or
            // So its or pointer remains null
            // Otherwise we need to recreate it below
            mfRejectedOverload.clearOr();
            MessageFilter mf = mfAccepted.or(mfRejectedLoop.or(mfRejectedOverload));
            
            // Send to next node
            
            next.send(req);
            
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            Message msg;
            try {
                msg = node.usm.waitFor(mf);
            } catch (DisconnectedException e) {
                Logger.normal(this, "Disconnected from "+next+" while waiting for Accepted");
                continue;
            }
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            
            if(msg == null || msg.getSpec() == DMT.FNPRejectedOverload) {
                // Overload... hmmmm - propagate error back to source
                Logger.error(this, "Propagating "+msg+" back to source on "+this);
                next.insertRejectedOverload();
                finish(REJECTED_OVERLOAD, next);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPRejectedLoop) {
           		next.insertDidNotRejectOverload();
                // Loop - we don't want to send the data to this one
                continue;
            }
            
            // Otherwise must be an Accepted
            
            // Send them the data.
            // Which might be the new data resulting from a collision...

            Message dataInsert;
            PartiallyReceivedBlock prbNow;
            prbNow = prb;
            dataInsert = DMT.createFNPDataInsert(uid, headers);
            bt = new BlockTransmitter(node.usm, next, uid, prbNow);
            /** What are we waiting for now??:
             * - FNPRouteNotFound - couldn't exhaust HTL, but send us the 
             *   data anyway please
             * - FNPInsertReply - used up all HTL, yay
             * - FNPRejectOverload - propagating an overload error :(
             * - FNPDataFound - target already has the data, and the data is
             *   an SVK/SSK/KSK, therefore could be different to what we are
             *   inserting.
             */
            
            MessageFilter mfRNF = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPRouteNotFound);
            MessageFilter mfInsertReply = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPInsertReply);
            mfRejectedOverload.setTimeout(PUT_TIMEOUT);
            mfRejectedOverload.clearOr();
            MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPRouteNotFound);
            MessageFilter mfDataInsertRejected = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPDataInsertRejected);
            MessageFilter mfTimeout = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPRejectedTimeout);
            
            mf = mfRNF.or(mfInsertReply.or(mfRouteNotFound.or(mfDataInsertRejected.or(mfTimeout.or(mfRejectedOverload)))));

            Logger.minor(this, "Sending DataInsert");
            if(receiveFailed) return;
            next.send(dataInsert);

            Logger.minor(this, "Sending data");
            if(receiveFailed) return;
            Sender s = new Sender();
            Thread senderThread = new Thread(s);
            senderThread.setDaemon(true);
            senderThread.start();
            senderThreads.add(senderThread);
            
            if(receiveFailed) return;
            try {
                msg = node.usm.waitFor(mf);
            } catch (DisconnectedException e) {
                Logger.normal(this, "Disconnected from "+next+" while waiting for InsertReply on "+this);
                continue;
            }
            if(receiveFailed) return;
            
            if(msg == null) {
                // Timeout :(
                // Fairly serious problem
                Logger.error(this, "Timeout after Accepted in insert");
                // Treat as rejected-overload
           		next.insertRejectedOverload();
                finish(REJECTED_OVERLOAD, next);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPRejectedOverload || msg.getSpec() == DMT.FNPRejectedTimeout) {
                Logger.minor(this, "Rejected due to overload");
           		next.insertRejectedOverload();
                finish(REJECTED_OVERLOAD, next);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPRouteNotFound) {
                Logger.minor(this, "Rejected: RNF");
                short newHtl = msg.getShort(DMT.HTL);
                if(htl > newHtl) htl = newHtl;
                // Finished as far as this node is concerned
           		next.insertDidNotRejectOverload();
                continue;
            }
            
            if(msg.getSpec() == DMT.FNPDataInsertRejected) {
           		next.insertDidNotRejectOverload();
                short reason = msg.getShort(DMT.DATA_INSERT_REJECTED_REASON);
                Logger.minor(this, "DataInsertRejected: "+reason);
                
                if(reason == DMT.DATA_INSERT_REJECTED_VERIFY_FAILED) {
                    if(fromStore) {
                        // That's odd...
                        Logger.error(this, "Verify failed on next node "+next+" for DataInsert but we were sending from the store!");
                    } else {
                        try {
                            if(!prb.allReceived())
                                Logger.error(this, "Did not receive all packets but next node says invalid anyway!");
                            else {
                                // Check the data
                                new CHKBlock(prb.getBlock(), headers, myKey);
                                Logger.error(this, "Verify failed on "+next+" but data was valid!");
                            }
                        } catch (CHKVerifyException e) {
                            Logger.normal(this, "Verify failed because data was invalid");
                        }
                    }
                    continue; // What else can we do?
                } else if(reason == DMT.DATA_INSERT_REJECTED_RECEIVE_FAILED) {
                    if(receiveFailed) {
                        Logger.minor(this, "Failed to receive data, so failed to send data");
                    } else {
                        if(prb.allReceived()) {
                            Logger.error(this, "Received all data but send failed to "+next);
                        } else {
                            if(prb.isAborted()) {
                                Logger.normal(this, "Send failed: aborted: "+prb.getAbortReason()+": "+prb.getAbortDescription());
                            } else
                                Logger.normal(this, "Send failed; have not yet received all data but not aborted: "+next);
                        }
                    }
                    continue;
                }
                
                Logger.error(this, "DataInsert rejected! Reason="+DMT.getDataInsertRejectedReason(reason));
                
            }
            
            if(msg.getSpec() != DMT.FNPInsertReply) {
            	Logger.error(this, "Unknown reply: "+msg);
            	finish(INTERNAL_ERROR, next);
            }
            
            // Our task is complete
       		next.insertDidNotRejectOverload();
            finish(SUCCESS, next);
            return;
        }
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
            if(status == NOT_FINISHED)
            	finish(INTERNAL_ERROR, null);
        } finally {
            node.completed(uid);
        	node.removeInsertSender(myKey, origHTL, this);
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
    
    private void finish(int code, PeerNode next) {
        Logger.minor(this, "Finished: "+code+" on "+this, new Exception("debug"));
        if(status != NOT_FINISHED)
        	throw new IllegalStateException("finish() called with "+code+" when was already "+status);
        status = code;
        
        for(Iterator i = senderThreads.iterator();i.hasNext();) {
        	Thread senderThread = (Thread) i.next();
        	while(senderThread.isAlive()) {
        		try {
        			senderThread.join();
        		} catch (InterruptedException e) {
        			// Ignore
        		}
        	}
        }
        
        if(status == REJECTED_OVERLOAD) {
        	node.getInsertThrottle().requestRejectedOverload();
        } else if(status == SUCCESS || status == ROUTE_NOT_FOUND) {
        	node.getInsertThrottle().requestCompleted(System.currentTimeMillis() - startTime);
        }
        
        synchronized(this) {
            notifyAll();
        }
    }

    public int getStatus() {
        return status;
    }
    
    public short getHTL() {
        return htl;
    }

    /**
     * Called by InsertHandler to notify that the receive has
     * failed.
     */
    public void receiveFailed() {
        receiveFailed = true;
    }

    /**
     * @return The current status as a string
     */
    public String getStatusString() {
        if(status == SUCCESS)
            return "SUCCESS";
        if(status == ROUTE_NOT_FOUND)
            return "ROUTE NOT FOUND";
        if(status == REJECTED_OVERLOAD)
            return "REJECTED: OVERLOAD";
        if(status == NOT_FINISHED)
            return "NOT FINISHED";
        if(status == INTERNAL_ERROR)
        	return "INTERNAL ERROR";
        return "UNKNOWN STATUS CODE: "+status;
    }
}
