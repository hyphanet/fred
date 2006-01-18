package freenet.node;

import java.util.HashSet;
import java.util.Vector;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.AbortedException;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.NodeCHK;
import freenet.support.Logger;

public final class CHKInsertSender implements Runnable, AnyInsertSender {

	private class AwaitingCompletion {
		
		/** Node we are waiting for response from */
		final PeerNode pn;
		/** We may be sending data to that node */
		BlockTransmitter bt;
		/** Have we received notice of the downstream success
		 * or failure of dependant transfers from that node?
		 * Includes timing out. */
		boolean receivedCompletionNotice = false;
		/** Timed out - didn't receive completion notice in
		 * the allotted time?? */
		boolean completionTimedOut = false;
		/** Was the notification of successful transfer? */
		boolean completionSucceeded;
		
		/** Have we completed the immediate transfer? */
		boolean completedTransfer = false;
		/** Did it succeed? */
		boolean transferSucceeded = false;
		
		AwaitingCompletion(PeerNode pn, PartiallyReceivedBlock prb) {
			this.pn = pn;
			bt = new BlockTransmitter(node.usm, pn, uid, prb);
			Sender s = new Sender(this);
            Thread senderThread = new Thread(s, "Sender for "+uid+" to "+pn.getPeer());
            senderThread.setDaemon(true);
            senderThread.start();
		}
		
		void completed(boolean timeout, boolean success) {
			synchronized(this) {
				if(timeout)
					completionTimedOut = true;
				else
					completionSucceeded = success;
				receivedCompletionNotice = true;
				notifyAll();
			}
			synchronized(nodesWaitingForCompletion) {
				nodesWaitingForCompletion.notifyAll();
			}
			if(!success) {
				synchronized(CHKInsertSender.this) {
					transferTimedOut = true;
					CHKInsertSender.this.notifyAll();
				}
			}
		}
		
		void completedTransfer(boolean success) {
			synchronized(this) {
				transferSucceeded = success;
				completedTransfer = true;
				notifyAll();
			}
			synchronized(nodesWaitingForCompletion) {
				nodesWaitingForCompletion.notifyAll();
			}
			if(!success) {
				synchronized(CHKInsertSender.this) {
					transferTimedOut = true;
					CHKInsertSender.this.notifyAll();
				}
			}
		}
	}
	
    public class Sender implements Runnable {
    	
    	final AwaitingCompletion completion;
    	final BlockTransmitter bt;
    	
    	public Sender(AwaitingCompletion ac) {
    		this.bt = ac.bt;
    		this.completion = ac;
    	}
    	
		public void run() {
			try {
				bt.send();
				if(bt.failedDueToOverload()) {
					completion.completedTransfer(false);
				} else {
					completion.completedTransfer(true);
				}
			} catch (Throwable t) {
				completion.completedTransfer(false);
				Logger.error(this, "Caught "+t, t);
			}
		}
	}
    
	CHKInsertSender(NodeCHK myKey, long uid, byte[] headers, short htl, 
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
        this.nodesWaitingForCompletion = new Vector();
        Thread t = new Thread(this, "CHKInsertSender for UID "+uid+" on "+node.portNumber+" at "+System.currentTimeMillis());
        t.setDaemon(true);
        t.start();
    }
    
    // Constants
    static final int ACCEPTED_TIMEOUT = 10000;
    static final int SEARCH_TIMEOUT = 60000;
    static final int TRANSFER_COMPLETION_TIMEOUT = 120000;

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
    private boolean sentRequest;
    
    /** List of nodes we are waiting for either a transfer completion
     * notice or a transfer completion from. */
    private Vector nodesWaitingForCompletion;
    
    /** Have all transfers completed and all nodes reported completion status? */
    private boolean allTransfersCompleted = false;
    
    /** Has a transfer timed out, either directly or downstream? */
    private boolean transferTimedOut = false;
    
    /** Runnable which waits for completion of all transfers */
    private CompletionWaiter cw = null;

    /** Time at which we set status to a value other than NOT_FINISHED */
    private long setStatusTime = -1;
    
    
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
        	realRun();
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
            if(status == NOT_FINISHED)
            	finish(INTERNAL_ERROR, null);
        } finally {
            node.completed(uid);
        	node.removeInsertSender(myKey, origHTL, this);
        }
    }
    
    private void realRun() {
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
            
            try {
				next.send(req);
			} catch (NotConnectedException e1) {
				Logger.minor(this, "Not connected to "+next);
				continue;
			}
            sentRequest = true;
            
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            Message msg = null;
            
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
					Logger.minor(this, "Timeout");
					next.localRejectedOverload();
					finish(TIMED_OUT, next);
					return;
				}
				
				if (msg.getSpec() == DMT.FNPRejectedOverload) {
					// Non-fatal - probably still have time left
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						next.localRejectedOverload();
						Logger.minor(this,
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
            /** What are we waiting for now??:
             * - FNPRouteNotFound - couldn't exhaust HTL, but send us the 
             *   data anyway please
             * - FNPInsertReply - used up all HTL, yay
             * - FNPRejectOverload - propagating an overload error :(
             * - FNPRejectTimeout - we took too long to send the DataInsert
             * - FNPDataInsertRejected - the insert was invalid
             */
            
            MessageFilter mfInsertReply = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SEARCH_TIMEOUT).setType(DMT.FNPInsertReply);
            mfRejectedOverload.setTimeout(SEARCH_TIMEOUT);
            mfRejectedOverload.clearOr();
            MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SEARCH_TIMEOUT).setType(DMT.FNPRouteNotFound);
            MessageFilter mfDataInsertRejected = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SEARCH_TIMEOUT).setType(DMT.FNPDataInsertRejected);
            MessageFilter mfTimeout = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SEARCH_TIMEOUT).setType(DMT.FNPRejectedTimeout);
            
            mf = mfInsertReply.or(mfRouteNotFound.or(mfDataInsertRejected.or(mfTimeout.or(mfRejectedOverload))));

            Logger.minor(this, "Sending DataInsert");
            if(receiveFailed) return;
            try {
				next.send(dataInsert);
			} catch (NotConnectedException e1) {
				Logger.minor(this, "Not connected sending DataInsert: "+next+" for "+uid);
				continue;
			}

            Logger.minor(this, "Sending data");
            if(receiveFailed) return;
            AwaitingCompletion ac = new AwaitingCompletion(next, prbNow);
            synchronized(nodesWaitingForCompletion) {
            	nodesWaitingForCompletion.add(ac);
            	nodesWaitingForCompletion.notifyAll();
            }
            makeCompletionWaiter();

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
							} catch (AbortedException e) {
								receiveFailed = true;
							}
						}
						break; // What else can we do?
					} else if (reason == DMT.DATA_INSERT_REJECTED_RECEIVE_FAILED) {
						if (receiveFailed) {
							Logger.minor(this, "Failed to receive data, so failed to send data");
						} else {
							try {
								if (prb.allReceived()) {
									Logger.error(this, "Received all data but send failed to " + next);
								} else {
									if (prb.isAborted()) {
										Logger.normal(this, "Send failed: aborted: " + prb.getAbortReason() + ": " + prb.getAbortDescription());
									} else
										Logger.normal(this, "Send failed; have not yet received all data but not aborted: " + next);
								}
							} catch (AbortedException e) {
								receiveFailed = true;
							}
						}
					}
					Logger.error(this, "DataInsert rejected! Reason="
							+ DMT.getDataInsertRejectedReason(reason));
					break;
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

        setStatusTime = System.currentTimeMillis();
        
        if(code == ROUTE_NOT_FOUND && !sentRequest)
        	code = ROUTE_REALLY_NOT_FOUND;
        
        status = code;
        
        synchronized(this) {
            notifyAll();
        }

        Logger.minor(this, "Set status code: "+getStatusString()+" on "+uid);
        
        // Now wait for transfers, or for downstream transfer notifications.
        
        synchronized(this) {
        	if(cw != null) {
        		while(!allTransfersCompleted) {
        			try {
        				wait(10*1000);
        			} catch (InterruptedException e) {
        				// Try again
        			}
        		}
        	} else {
        		// There weren't any transfers
        		allTransfersCompleted = true;
        	}
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
	
	private synchronized void makeCompletionWaiter() {
		if(cw == null) {
			cw = new CompletionWaiter();
			Thread t = new Thread(cw, "Completion waiter for "+uid);
			t.setDaemon(true);
			t.start();
		}
	}
	
	private class CompletionWaiter implements Runnable {
		
		public void run() {
			Logger.minor(this, "Starting "+this);
outer:		while(true) {
			AwaitingCompletion[] waiters;
			synchronized(nodesWaitingForCompletion) {
				waiters = new AwaitingCompletion[nodesWaitingForCompletion.size()];
				waiters = (AwaitingCompletion[]) nodesWaitingForCompletion.toArray(waiters);
			}
			
			// First calculate the timeout
			
			int timeout;
			boolean noTimeLeft = false;

			long now = System.currentTimeMillis();
			if(status == NOT_FINISHED) {
				// Wait 5 seconds, then try again
				timeout = 5000;
			} else {
				// Completed, wait for everything
				timeout = (int)Math.min(Integer.MAX_VALUE, (setStatusTime + TRANSFER_COMPLETION_TIMEOUT) - now);
			}
			if(timeout <= 0) {
				noTimeLeft = true;
				timeout = 1;
			}
			
			MessageFilter mf = null;
			for(int i=0;i<waiters.length;i++) {
				AwaitingCompletion awc = waiters[i];
				if(!awc.pn.isConnected()) {
					Logger.normal(this, "Disconnected: "+awc.pn+" in "+CHKInsertSender.this);
					continue;
				}
				if(!awc.receivedCompletionNotice) {
					MessageFilter m =
						MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPInsertTransfersCompleted).setSource(awc.pn).setTimeout(timeout);
					if(mf == null)
						mf = m;
					else
						mf = m.or(mf);
					Logger.minor(this, "Waiting for "+awc.pn.getPeer());
				}
			}
			
			if(mf == null) {
				if(status != NOT_FINISHED) {
					if(nodesWaitingForCompletion.size() != waiters.length) {
						// Added another one
						Logger.minor(this, "Looping (mf==null): waiters="+waiters.length+" but waiting="+nodesWaitingForCompletion.size());
						continue;
					}
					if(waitForCompletedTransfers(waiters, timeout, noTimeLeft)) {
						synchronized(CHKInsertSender.this) {
							allTransfersCompleted = true;
							CHKInsertSender.this.notifyAll();
						}
						return;
					}
					if(noTimeLeft) {
						for(int i=0;i<waiters.length;i++) {
							if(!waiters[i].pn.isConnected()) continue;
							if(!waiters[i].completedTransfer) {
								waiters[i].completedTransfer(false);
							}
						}
						synchronized(CHKInsertSender.this) {
							allTransfersCompleted = true;
							CHKInsertSender.this.notifyAll();
						}
						return;
					}
					// Otherwise, not finished, go back around loop
					continue;
				} else {
					// Still waiting for request completion, so more may be added
					synchronized(nodesWaitingForCompletion) {
						try {
							nodesWaitingForCompletion.wait(timeout);
						} catch (InterruptedException e) {
							// Go back around the loop
						}
					}
				}
				continue;
			} else {
				Message m;
				try {
					m = node.usm.waitFor(mf);
				} catch (DisconnectedException e) {
					// Which one? I have no idea.
					// Go around the loop again.
					continue;
				}
				if(m != null) {
					// Process message
					PeerNode pn = (PeerNode) m.getSource();
					boolean processed = false;
					for(int i=0;i<waiters.length;i++) {
						PeerNode p = waiters[i].pn;
						if(p == pn) {
							boolean anyTimedOut = m.getBoolean(DMT.ANY_TIMED_OUT);
							waiters[i].completed(false, !anyTimedOut);
							if(anyTimedOut) {
								synchronized(CHKInsertSender.this) {
									if(!transferTimedOut) {
										transferTimedOut = true;
										CHKInsertSender.this.notifyAll();
									}
								}
							}
							processed = true;
							break;
						}
					}
					if(!processed) {
						Logger.error(this, "Did not process message: "+m+" on "+this);
					}
				} else {
					if(nodesWaitingForCompletion.size() > waiters.length) {
						// Added another one
						Logger.minor(this, "Looping: waiters="+waiters.length+" but waiting="+nodesWaitingForCompletion.size());
						continue;
					}
					if(noTimeLeft) {
						Logger.minor(this, "Overall timeout on "+CHKInsertSender.this);
						for(int i=0;i<waiters.length;i++) {
							if(!waiters[i].pn.isConnected()) continue;
							if(!waiters[i].receivedCompletionNotice)
								waiters[i].completed(false, false);
							if(!waiters[i].completedTransfer)
								waiters[i].completedTransfer(false);
						}
						synchronized(CHKInsertSender.this) {
							transferTimedOut = true;
							allTransfersCompleted = true;
							CHKInsertSender.this.notifyAll();
						}
						return;
					}
				}
			}
		}
		}

		/** @return True if all transfers have completed, false otherwise. */
		private boolean waitForCompletedTransfers(AwaitingCompletion[] waiters, int timeout, boolean noTimeLeft) {
			// MAYBE all done
			boolean completedTransfers = true;
			synchronized(nodesWaitingForCompletion) {
				for(int i=0;i<waiters.length;i++) {
					if(!waiters[i].pn.isConnected()) continue;
					if(!waiters[i].completedTransfer) {
						completedTransfers = false;
						break;
					}
				}
				if(!completedTransfers) {
					try {
						if(!noTimeLeft) {
							nodesWaitingForCompletion.wait(timeout);
						} else {
							// Timed out
						}
						completedTransfers = true;
						for(int i=0;i<waiters.length;i++) {
							if(!waiters[i].pn.isConnected()) continue;
							if(!waiters[i].completedTransfer) {
								completedTransfers = false;
								break;
							}
						}
					} catch (InterruptedException e) {
						// Ignore
					}
				}
			}
			if(completedTransfers) {
				// All done!
				Logger.minor(this, "Completed, status="+getStatusString()+", nothing left to wait for.");
				synchronized(CHKInsertSender.this) {
					allTransfersCompleted = true;
					CHKInsertSender.this.notifyAll();
				}
				return true;
			} else return false;
		}

		public String toString() {
			return super.toString()+" for "+uid;
		}
	}

	public boolean completed() {
		return allTransfersCompleted;
	}

	public boolean anyTransfersFailed() {
		return transferTimedOut;
	}

	public byte[] getPubkeyHash() {
		return headers;
	}

	public byte[] getHeaders() {
		return headers;
	}
}
