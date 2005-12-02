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

    	public Sender(BlockTransmitter bt) {
    		this.bt = bt;
    	}

    	// We will often have multiple simultaneous senders, so we need them to send separately.
    	final BlockTransmitter bt;
    	
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
        blockSenders = new LinkedList();
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
    private final LinkedList blockSenders;
    private boolean sentRequest;
    
    private int status = -1;
    /** Still running */
    static final int NOT_FINISHED = -1;
    /** Successful insert */
    static final int SUCCESS = 0;
    /** Route not found */
    static final int ROUTE_NOT_FOUND = 1;
    /** Internal error */
    static final int INTERNAL_ERROR = 3;
    /** Timed out waiting for response */
    static final int TIMED_OUT = 4;
    /** Locally Generated a RejectedOverload */
    static final int GENERATED_REJECTED_OVERLOAD = 5;
    /** Could not get off the node at all! */
    static final int ROUTE_REALLY_NOT_FOUND = 6;
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public void run() {
        short origHTL = htl;
        try {
        HashSet nodesRoutedTo = new HashSet();
        HashSet nodesNotIgnored = new HashSet();
        
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
                next = node.peers.closerPeer(source, nodesRoutedTo, nodesNotIgnored, target, true);
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
            sentRequest = true;
            
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            Message msg;
            
            /*
             * Because messages may be re-ordered, it is
             * entirely possible that we get a non-local RejectedOverload,
             * followed by an Accepted. So we must loop here.
             */
            
            while (true) {
            	
				try {
					msg = node.usm.waitFor(mf);
				} catch (DisconnectedException e) {
					Logger.normal(this, "Disconnected from " + next
							+ " while waiting for Accepted");
					break;
				}
				
				if (receiveFailed)
					return; // don't need to set status as killed by InsertHandler
				
				if (msg == null) {
					// Terminal overload
					// Try to propagate back to source
					next.localRejectedOverload();
					finish(TIMED_OUT, next);
					return;
				}
				
				if (msg.getSpec() == DMT.FNPRejectedOverload) {
					// Non-fatal - probably still have time left
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						next.localRejectedOverload();
						Logger
								.minor(this,
										"Local RejectedOverload, moving on to next peer");
						// Give up on this one, try another
						break;
					} else {
						forwardRejectedOverload();
					}
					continue;
				}
				
				if (msg.getSpec() == DMT.FNPRejectedLoop) {
					next.successNotOverload();
					// Loop - we don't want to send the data to this one
					break;
				}
				
				if (msg.getSpec() != DMT.FNPAccepted) {
					Logger.error(this,
							"Unexpected message waiting for Accepted: "
									+ msg);
					break;
				}
				// Otherwise is an FNPAccepted
				break;
			}
            
            if(msg == null || msg.getSpec() != DMT.FNPAccepted) continue;
            
            Logger.minor(this, "Got Accepted on "+this);
            
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
            
            MessageFilter mfInsertReply = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPInsertReply);
            mfRejectedOverload.setTimeout(PUT_TIMEOUT);
            mfRejectedOverload.clearOr();
            MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPRouteNotFound);
            MessageFilter mfDataInsertRejected = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPDataInsertRejected);
            MessageFilter mfTimeout = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(PUT_TIMEOUT).setType(DMT.FNPRejectedTimeout);
            
            mf = mfInsertReply.or(mfRouteNotFound.or(mfDataInsertRejected.or(mfTimeout.or(mfRejectedOverload))));

            Logger.minor(this, "Sending DataInsert");
            if(receiveFailed) return;
            next.send(dataInsert);

            Logger.minor(this, "Sending data");
            if(receiveFailed) return;
            Sender s = new Sender(bt);
            Thread senderThread = new Thread(s, "Sender for "+uid+" to "+next.getPeer());
            senderThread.setDaemon(true);
            senderThread.start();
            senderThreads.add(senderThread);
            blockSenders.add(bt);

            while (true) {

				if (receiveFailed)
					return;
				
				try {
					msg = node.usm.waitFor(mf);
				} catch (DisconnectedException e) {
					Logger.normal(this, "Disconnected from " + next
							+ " while waiting for InsertReply on " + this);
					break;
				}
				if (receiveFailed)
					return;
				
				if (msg == null || msg.getSpec() == DMT.FNPRejectedTimeout) {
					// Timeout :(
					// Fairly serious problem
					Logger.error(this, "Timeout (" + msg
							+ ") after Accepted in insert");
					// Terminal overload
					// Try to propagate back to source
					next.localRejectedOverload();
					finish(TIMED_OUT, next);
					return;
				}

				if (msg.getSpec() == DMT.FNPRejectedOverload) {
					// Probably non-fatal, if so, we have time left, can try next one
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						next.localRejectedOverload();
						Logger.minor(this,
								"Local RejectedOverload, moving on to next peer");
						// Give up on this one, try another
						break;
					} else {
						forwardRejectedOverload();
					}
					continue; // Wait for any further response
				}

				if (msg.getSpec() == DMT.FNPRouteNotFound) {
					Logger.minor(this, "Rejected: RNF");
					short newHtl = msg.getShort(DMT.HTL);
					if (htl > newHtl)
						htl = newHtl;
					// Finished as far as this node is concerned
					next.successNotOverload();
					break;
				}

				if (msg.getSpec() == DMT.FNPDataInsertRejected) {
					next.successNotOverload();
					short reason = msg
							.getShort(DMT.DATA_INSERT_REJECTED_REASON);
					Logger.minor(this, "DataInsertRejected: " + reason);
						if (reason == DMT.DATA_INSERT_REJECTED_VERIFY_FAILED) {
						if (fromStore) {
							// That's odd...
							Logger.error(this,"Verify failed on next node "
									+ next + " for DataInsert but we were sending from the store!");
						} else {
							try {
								if (!prb.allReceived())
									Logger.error(this,
											"Did not receive all packets but next node says invalid anyway!");
								else {
									// Check the data
									new CHKBlock(prb.getBlock(), headers,
											myKey);
									Logger.error(this,
											"Verify failed on " + next
											+ " but data was valid!");
								}
							} catch (CHKVerifyException e) {
								Logger
										.normal(this,
												"Verify failed because data was invalid");
							}
						}
						break; // What else can we do?
					} else if (reason == DMT.DATA_INSERT_REJECTED_RECEIVE_FAILED) {
						if (receiveFailed) {
							Logger.minor(this, "Failed to receive data, so failed to send data");
						} else {
							if (prb.allReceived()) {
								Logger.error(this, "Received all data but send failed to " + next);
							} else {
								if (prb.isAborted()) {
									Logger.normal(this, "Send failed: aborted: " + prb.getAbortReason() + ": " + prb.getAbortDescription());
								} else
									Logger.normal(this, "Send failed; have not yet received all data but not aborted: " + next);
							}
						}
						break;
					}
					Logger.error(this, "DataInsert rejected! Reason="
						+ DMT.getDataInsertRejectedReason(reason));
				}
				
				if (msg.getSpec() != DMT.FNPInsertReply) {
					Logger.error(this, "Unknown reply: " + msg);
					finish(INTERNAL_ERROR, next);
				}
				
				// Our task is complete
				next.successNotOverload();
				finish(SUCCESS, next);
				return;
			}
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
    
    private boolean hasForwardedRejectedOverload = false;
    
    synchronized boolean receivedRejectedOverload() {
    	return hasForwardedRejectedOverload;
    }
    
    /** Forward RejectedOverload to the request originator.
     * DO NOT CALL if have a *local* RejectedOverload.
     */
    private synchronized void forwardRejectedOverload() {
    	if(hasForwardedRejectedOverload) return;
    	hasForwardedRejectedOverload = true;
   		notifyAll();
	}
    
    private void finish(int code, PeerNode next) {
        Logger.minor(this, "Finished: "+code+" on "+this, new Exception("debug"));
        if(status != NOT_FINISHED)
        	throw new IllegalStateException("finish() called with "+code+" when was already "+status);

        for(Iterator i = blockSenders.iterator();i.hasNext();) {
        	BlockTransmitter bt = (BlockTransmitter) i.next();
        	Logger.minor(this, "Waiting for "+bt);
        	bt.waitForComplete();
        	if(bt.failedDueToOverload() && (status == SUCCESS || status == ROUTE_NOT_FOUND)) {
        		forwardRejectedOverload();
        		((PeerNode)bt.getDestination()).localRejectedOverload();
        		break;
        	}
        }
        
        if(code == ROUTE_NOT_FOUND && blockSenders.isEmpty())
        	code = ROUTE_REALLY_NOT_FOUND;
        
        status = code;
        
        synchronized(this) {
            notifyAll();
        }
        Logger.minor(this, "Returning from finish()");
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
        if(status == NOT_FINISHED)
            return "NOT FINISHED";
        if(status == INTERNAL_ERROR)
        	return "INTERNAL ERROR";
        if(status == TIMED_OUT)
        	return "TIMED OUT";
        if(status == GENERATED_REJECTED_OVERLOAD)
        	return "GENERATED REJECTED OVERLOAD";
        if(status == ROUTE_REALLY_NOT_FOUND)
        	return "ROUTE REALLY NOT FOUND";
        return "UNKNOWN STATUS CODE: "+status;
    }

	public boolean sentRequest() {
		return sentRequest;
	}
}
