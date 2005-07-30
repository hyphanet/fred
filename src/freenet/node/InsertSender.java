package freenet.node;

import java.util.HashSet;
import java.util.Iterator;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.RetrievalException;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.NodeCHK;
import freenet.support.Logger;
import freenet.support.ShortBuffer;

public class InsertSender implements Runnable {

    InsertSender(NodeCHK myKey, long uid, byte[] headers, short htl, 
            NodePeer source, Node node, PartiallyReceivedBlock prb, boolean fromStore) {
        this.myKey = myKey;
        this.target = myKey.toNormalizedDouble();
        this.uid = uid;
        this.headers = headers;
        this.htl = htl;
        this.source = source;
        this.node = node;
        this.prb = prb;
        this.fromStore = fromStore;
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
    final PartiallyReceivedBlock prb;
    final boolean fromStore;
    /** Used if we receive data as result of a DataReply */
    private PartiallyReceivedBlock dataReplyPRB = null;
    private byte[] dataReplyHeaders = null;
    private Throwable dataReplyReceiveThrew = null;
    private boolean receiveFailed = false;
    
    private int status = -1;
    static final int NOT_FINISHED = -1;
    static final int SUCCESS = 0;
    static final int ROUTE_NOT_FOUND = 1;
    static final int REJECTED_OVERLOAD = 2;
    static final int REPLIED_WITH_DATA = 3;
    
    public void run() {
        
        Message req = DMT.createFNPInsertRequest(uid, htl, myKey);
        
        HashSet nodesRoutedTo = new HashSet();
        HashSet nodesToSendDataTo = null;
        
        while(true) {
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            
            if(htl == 0) {
                // Send the data on to nodesToSendDataTo
                if(nodesToSendDataTo != null) {
                    for(Iterator i=nodesToSendDataTo.iterator();i.hasNext();) {
                        NodePeer pn = (NodePeer) i.next();
                        BlockTransmitter bt = new BlockTransmitter(node.usm, pn, uid, prb);
                        bt.sendAsync();
                    }
                }
                
                // Send an InsertReply back
                finish(SUCCESS);
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
            
            // Wait for ack or reject... will come before even a locally generated DataReply
            
            MessageFilter mfAccepted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
            MessageFilter mfRejectedLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedLoop);
            MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedOverload);
            MessageFilter mfDataFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPDataFound);
            
            // mfRejectedOverload must be the last thing in the or
            // So its or pointer remains null
            // Otherwise we need to recreate it below
            MessageFilter mf = mfAccepted.or(mfRejectedLoop.or(mfRejectedOverload.or(mfDataFound)));
            mfRejectedOverload.clearOr();
            
            // Send to next node
            
            next.send(req);
            
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            Message msg = node.usm.waitFor(mf);
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            
            if(msg == null) {
                // No response, move on
                htl = node.decrementHTL(source, htl);
                continue;
            }
            
            if(msg.getSpec() == DMT.FNPRejectedLoop) {
                // Loop - we don't want to send the data to this one
                htl = node.decrementHTL(source, htl);
                continue;
            }
            
            if(msg.getSpec() == DMT.FNPRejectedOverload) {
                // Overload... hmmmm - propagate error back to source
                finish(REJECTED_OVERLOAD);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPDataFound) {
                // Could happen early
                // FIXME what now??
            }
            
            // Otherwise must be an Accepted
            
            // Send them the data.
            // Which might be the new data resulting from a collision...

            BlockTransmitter bt;
            Message dataInsert;
            PartiallyReceivedBlock prbNow;
            if(dataReplyPRB != null) {
                prbNow = dataReplyPRB;
                dataInsert = DMT.createFNPDataInsert(uid, dataReplyHeaders);
            } else {
                prbNow = prb;
                dataInsert = DMT.createFNPDataInsert(uid, headers);
            }
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
            mfDataFound.setTimeout(PUT_TIMEOUT);
            mfRejectedOverload.setTimeout(PUT_TIMEOUT);
            MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPRouteNotFound);
            MessageFilter mfDataInsertRejected = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPDataInsertRejected);
            
            mf = mfRNF.or(mfDataFound.or(mfInsertReply.or(mfRouteNotFound.or(mfDataInsertRejected.or(mfRejectedOverload.setTimeout(PUT_TIMEOUT))))));
            
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            next.send(dataInsert);

            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            bt.send();
            
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            msg = node.usm.waitFor(mf);
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            
            if(msg == null) {
                // Timeout :(
                // Fairly serious problem
                Logger.error(this, "Timeout after Accepted in insert");
                // Treat as rejected-overload
                finish(REJECTED_OVERLOAD);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPRejectedOverload) {
                finish(REJECTED_OVERLOAD);
                return;
            }
            
            if(msg.getSpec() == DMT.FNPRouteNotFound) {
                // Still gets the data - but not yet
                if(nodesToSendDataTo == null) nodesToSendDataTo = new HashSet();
                nodesToSendDataTo.add(next);
                short newHtl = msg.getShort(DMT.HTL);
                htl = node.decrementHTL(source, htl);
                if(htl > newHtl) htl = newHtl;
                continue;
            }
            
            if(msg.getSpec() == DMT.FNPDataFound) {
                // Target node already has the data!
                // Shouldn't happen for CHKs; unnecessary
                if(myKey instanceof NodeCHK) {
                    Logger.error(this, "Received "+msg+" for "+myKey);
                }
                if(dataReplyHeaders != null) {
                    // Already receiving it from another node!
                    // FIXME what now??
                }
                // Receive the data
                dataReplyHeaders = ((ShortBuffer)msg.getObject(DMT.BLOCK_HEADERS)).getData();
                dataReplyPRB = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
                final BlockReceiver br = new BlockReceiver(node.usm, next, uid, dataReplyPRB);
                // Who do we want to send it to?
                // We want to send it to the client
                // We want to send it to any nodes which have RNFed
                
                // Start an async receive for the data into the PRB
                // Don't care about status; that's the client's problem
                Runnable r = new Runnable() { 
                    public void run() { 
                        try {
                            br.receive();
                        } catch (RetrievalException e) {
                            dataReplyReceiveThrew = e;
                            // Already aborted
                        } catch (Throwable t) {
                            dataReplyReceiveThrew = t;
                            dataReplyPRB.abort(RetrievalException.UNKNOWN, "Caught "+t);
                        }
                        // If it succeeds, PRB will know it has completed.
                    }
                };
                Thread t1 = new Thread(r);
                t1.setDaemon(true);
                t1.start();

                // Nodes we have already started a data send to can
                // have the old version of the data
                // REDFLAG Should we send them the new version? Would
                // be a PITA, and doesn't seem vital...
                
                // Tell the client so he gets the data
                finish(REPLIED_WITH_DATA);
                // Continue to insert
            }
            
            if(msg.getSpec() == DMT.FNPDataInsertRejected) {
                short reason = msg.getShort(DMT.DATA_INSERT_REJECTED_REASON);
                
                if(reason == DMT.DATA_INSERT_REJECTED_VERIFY_FAILED) {
                    if(fromStore) {
                        // That's odd...
                        Logger.error(this, "Verify failed on next node for DataInsert but we were sending from the store!");
                    } else {
                        // Is the data invalid?
                        // FIXME
                    }
                } // FIXME other reasons
                
                Logger.error(this, "DataInsert rejected! Reason="+DMT.getDataInsertRejectedReason(reason));
                
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
        if(status == REPLIED_WITH_DATA) {
            // Ignore errors
            return;
        }
        status = code;
        synchronized(this) {
            notifyAll();
        }
    }

    public int getStatus() {
        return status;
    }
    
    public PartiallyReceivedBlock getDataReplyPRB() {
        return dataReplyPRB;
    }

    public byte[] getDataReplyHeaders() {
        return dataReplyHeaders;
    }

    public short getHTL() {
        return htl;
    }

    /**
     * Called by InsertHandler to notify that the receive has
     * failed.
     */
    public void receiveFailed() {
        if(dataReplyHeaders != null) {
            // Ignore because receiving not sending
            return;
        }
        receiveFailed = true;
    }
}
