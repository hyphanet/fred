/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashSet;
import java.util.Vector;

import freenet.io.comm.ByteCounter;
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
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.OOMHandler;

public final class CHKInsertSender implements Runnable, AnyInsertSender, ByteCounter {
	
	private class BackgroundTransfer implements Runnable {
		/** Node we are waiting for response from */
		final PeerNode pn;
		/** We may be sending data to that node */
		BlockTransmitter bt;
		/** Have we received notice of the downstream success
		 * or failure of dependant transfers from that node?
		 * Includes timing out. */
		boolean receivedCompletionNotice;

		/** Was the notification of successful transfer? */
		boolean completionSucceeded;
		
		/** Have we completed the immediate transfer? */
		boolean completedTransfer;
		/** Did it succeed? */
		boolean transferSucceeded;
		
		BackgroundTransfer(PeerNode pn, PartiallyReceivedBlock prb) {
			this.pn = pn;
			bt = new BlockTransmitter(node.usm, pn, uid, prb, node.outputThrottle, CHKInsertSender.this);
		}
		
		void start() {
			node.executor.execute(this, "CHKInsert-BackgroundTransfer for "+uid+" to "+pn.getPeer());
		}
		
		public void run() {
			freenet.support.Logger.OSThread.logPID(this);
			try {
				bt.send(node.executor);
				this.completedTransfer(bt.failedDueToOverload());
			} catch (Throwable t) {
				this.completedTransfer(false);
				Logger.error(this, "Caught "+t, t);
			}
		}
		
		void completedTransfer(boolean success) {
			synchronized(this) {
				transferSucceeded = success;
				completedTransfer = true;
				notifyAll();
			}
			synchronized(backgroundTransfers) {
				backgroundTransfers.notifyAll();
			}
			if(!success) {
				synchronized(CHKInsertSender.this) {
					transferTimedOut = true;
					CHKInsertSender.this.notifyAll();
				}
			}
		}
		
		void receivedNotice(boolean success) {
			synchronized(this) {
				completionSucceeded = success;
				receivedCompletionNotice = true;
				notifyAll();
			}
			synchronized(backgroundTransfers) {
				backgroundTransfers.notifyAll();
			}
			if(!success) {
				synchronized(CHKInsertSender.this) {
					transferTimedOut = true;
					CHKInsertSender.this.notifyAll();
				}
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
        this.backgroundTransfers = new Vector();
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    }

	void start() {
		node.executor.execute(this, "CHKInsertSender for UID "+uid+" on "+node.getDarknetPortNumber()+" at "+System.currentTimeMillis());
	}

	static boolean logMINOR;
	
    // Constants
    static final int ACCEPTED_TIMEOUT = 10000;
    static final int SEARCH_TIMEOUT = 120000;
    static final int TRANSFER_COMPLETION_ACK_TIMEOUT = 120000;

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
    private boolean receiveFailed;
    final double closestLocation;
    final long startTime;
    private boolean sentRequest;
    
    /** List of nodes we are waiting for either a transfer completion
     * notice or a transfer completion from. Also used as a sync object for waiting for transfer completion. */
    private Vector backgroundTransfers;
    
    /** Have all transfers completed and all nodes reported completion status? */
    private boolean allTransfersCompleted;
    
    /** Has a transfer timed out, either directly or downstream? */
    private boolean transferTimedOut;
    
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
    /** Receive failed. Not used internally; only used by CHKInsertHandler. */
    static final int RECEIVE_FAILED = 7;
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
        short origHTL;
    	synchronized (this) {
            origHTL = htl;
		}

        node.addInsertSender(myKey, origHTL, this);
        try {
        	realRun();
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
        } finally {
        	// Always check: we ALWAYS set status, even if receiveFailed.
            int myStatus;
            synchronized (this) {
				myStatus = status;
			}
            if(myStatus == NOT_FINISHED)
            	finish(INTERNAL_ERROR, null);
        	node.removeInsertSender(myKey, origHTL, this);
        }
    }
    
    private void realRun() {
        HashSet nodesRoutedTo = new HashSet();
        HashSet nodesNotIgnored = new HashSet();
        
        while(true) {
            if(receiveFailed) {
            	return; // don't need to set status as killed by InsertHandler
            }
            
            synchronized (this) {
            	if(htl == 0) {
            		// Send an InsertReply back
            		finish(SUCCESS, null);
            		return;
            	}
            }
            // Route it
            PeerNode next;
            // Can backtrack, so only route to nodes closer than we are to target.
            double nextValue;
            next = node.peers.closerPeer(source, nodesRoutedTo, nodesNotIgnored, target, true, node.isAdvancedModeEnabled(), -1, null);
			
            if(next == null) {
                // Backtrack
                finish(ROUTE_NOT_FOUND, null);
                return;
            }
			
			nextValue = next.getLocation();
			
            if(logMINOR) Logger.minor(this, "Routing insert to "+next);
            nodesRoutedTo.add(next);
            
            Message req;
            synchronized (this) {
            	if(Location.distance(target, nextValue) > Location.distance(target, closestLocation)) {
            		if(logMINOR) Logger.minor(this, "Backtracking: target="+target+" next="+nextValue+" closest="+closestLocation);
            		htl = node.decrementHTL(source, htl);
            	}

            	req = DMT.createFNPInsertRequest(uid, htl, myKey, closestLocation);
            }
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
				next.sendSync(req, this);
			} catch (NotConnectedException e1) {
				if(logMINOR) Logger.minor(this, "Not connected to "+next);
				continue;
			}
			synchronized (this) {
				sentRequest = true;				
			}
            
            if(receiveFailed) return; // don't need to set status as killed by InsertHandler
            Message msg = null;
            
            /*
             * Because messages may be re-ordered, it is
             * entirely possible that we get a non-local RejectedOverload,
             * followed by an Accepted. So we must loop here.
             */
            
            while ((msg==null) || (msg.getSpec() != DMT.FNPAccepted)) {
            	
				try {
					msg = node.usm.waitFor(mf, this);
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
					if(logMINOR) Logger.minor(this, "Timeout");
					next.localRejectedOverload("Timeout3");
					// Try another node.
					forwardRejectedOverload();
					break;
				}
				
				if (msg.getSpec() == DMT.FNPRejectedOverload) {
					// Non-fatal - probably still have time left
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						next.localRejectedOverload("ForwardRejectedOverload5");
						if(logMINOR) Logger.minor(this,
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
			}
            
            if((msg == null) || (msg.getSpec() != DMT.FNPAccepted)) continue;
            
            if(logMINOR) Logger.minor(this, "Got Accepted on "+this);
            
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

            if(logMINOR) Logger.minor(this, "Sending DataInsert");
            if(receiveFailed) return;
            try {
				next.sendSync(dataInsert, this);
			} catch (NotConnectedException e1) {
				if(logMINOR) Logger.minor(this, "Not connected sending DataInsert: "+next+" for "+uid);
				continue;
			}

			if(logMINOR) Logger.minor(this, "Sending data");
            if(receiveFailed) return;
            BackgroundTransfer ac = new BackgroundTransfer(next, prbNow);
            synchronized(backgroundTransfers) {
            	backgroundTransfers.add(ac);
            	backgroundTransfers.notifyAll();
            }
            ac.start();

            while (true) {

				if (receiveFailed)
					return;
				
				try {
					msg = node.usm.waitFor(mf, this);
				} catch (DisconnectedException e) {
					Logger.normal(this, "Disconnected from " + next
							+ " while waiting for InsertReply on " + this);
					break;
				}
				if (receiveFailed)
					return;
				
				if ((msg == null) || (msg.getSpec() == DMT.FNPRejectedTimeout)) {
					// Timeout :(
					// Fairly serious problem
					Logger.error(this, "Timeout (" + msg
							+ ") after Accepted in insert");
					// Terminal overload
					// Try to propagate back to source
					next.localRejectedOverload("AfterInsertAcceptedTimeout2");
					finish(TIMED_OUT, next);
					return;
				}

				if (msg.getSpec() == DMT.FNPRejectedOverload) {
					// Probably non-fatal, if so, we have time left, can try next one
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						next.localRejectedOverload("ForwardRejectedOverload6");
						if(logMINOR) Logger.minor(this,
								"Local RejectedOverload, moving on to next peer");
						// Give up on this one, try another
						break;
					} else {
						forwardRejectedOverload();
					}
					continue; // Wait for any further response
				}

				if (msg.getSpec() == DMT.FNPRouteNotFound) {
					if(logMINOR) Logger.minor(this, "Rejected: RNF");
					short newHtl = msg.getShort(DMT.HTL);
					synchronized (this) {
						if (htl > newHtl)
							htl = newHtl;						
					}
					// Finished as far as this node is concerned
					next.successNotOverload();
					break;
				}

				if (msg.getSpec() == DMT.FNPDataInsertRejected) {
					next.successNotOverload();
					short reason = msg
							.getShort(DMT.DATA_INSERT_REJECTED_REASON);
					if(logMINOR) Logger.minor(this, "DataInsertRejected: " + reason);
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
								Logger.normal(this,
												"Verify failed because data was invalid");
							} catch (AbortedException e) {
								receiveFailed();
							}
						}
						break; // What else can we do?
					} else if (reason == DMT.DATA_INSERT_REJECTED_RECEIVE_FAILED) {
						if (receiveFailed) {
							if(logMINOR) Logger.minor(this, "Failed to receive data, so failed to send data");
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
								receiveFailed();
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
					return;
				}else{
					// Our task is complete, one node (quite deep), has accepted the insert.
					next.successNotOverload();
					finish(SUCCESS, next);
					return;
				}
			}
			if (logMINOR) Logger.debug(this, "Trying alternate node for insert");
		}
	}

	private boolean hasForwardedRejectedOverload;
    
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
    
    /**
     * Finish the insert process. Will set status, wait for underlings to complete, and report success
     * if appropriate.
     * @param code The status code to set. 
     * @param next The node we successfully inserted to.
     */
    private void finish(int code, PeerNode next) {
    	if(logMINOR) Logger.minor(this, "Finished: "+code+" on "+this, new Exception("debug"));
     
        synchronized(this) {   
        	if((code == ROUTE_NOT_FOUND) && !sentRequest)
        		code = ROUTE_REALLY_NOT_FOUND;

        	if(status != NOT_FINISHED) {
        		if(status == RECEIVE_FAILED) {
        			if(code == SUCCESS)
        				Logger.error(this, "Request succeeded despite receive failed?! on "+this);
        		} else
        			throw new IllegalStateException("finish() called with "+code+" when was already "+status);
        	} else {
                status = code;
        	}
        	
        	notifyAll();
        	if(logMINOR) Logger.minor(this, "Set status code: "+getStatusString()+" on "+uid);
        }
		
        // Now wait for transfers, or for downstream transfer notifications.
		synchronized(backgroundTransfers) {
			if (!backgroundTransfers.isEmpty()) {
				waitForBackgroundTransferCompletions();
			} else {
				if(logMINOR) Logger.minor(this, "No background transfers");
			}
		}
        
        	synchronized(this) {
        		allTransfersCompleted = true;
        		notifyAll();
        	}
        
        if(status == SUCCESS && next != null)
        	next.onSuccess(true, false);
        
        if(logMINOR) Logger.minor(this, "Returning from finish()");
    }

    public synchronized int getStatus() {
        return status;
    }
    
    public synchronized short getHTL() {
        return htl;
    }

    /**
     * Called by InsertHandler to notify that the receive has
     * failed.
     */
    public void receiveFailed() {
    	synchronized(backgroundTransfers) {
    		receiveFailed = true;
    		backgroundTransfers.notifyAll();
    	}
    	// Set status immediately.
    	// The code (e.g. waitForStatus()) relies on a status eventually being set,
    	// so we may as well set it here. The alternative is to set it in realRun()
    	// when we notice that receiveFailed = true.
    	synchronized(this) {
    		status = RECEIVE_FAILED;
    		allTransfersCompleted = true;
			//FIXME: Won't this leak unclaimed FIFO elements?
    		backgroundTransfers.clear(); // Effectively ... we certainly don't want to wait for it.
    		notifyAll();
    	}
    	// Do not call finish(), that can only be called on the main thread and it will block.
    }

    /**
     * @return The current status as a string
     */
    public synchronized String getStatusString() {
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

	public synchronized boolean sentRequest() {
		return sentRequest;
	}
		
		public void waitForBackgroundTransferCompletions() {
			try {
		    freenet.support.Logger.OSThread.logPID(this);
			if(logMINOR) Logger.minor(this, "Starting "+this);
			
			// We are presently at a terminal stage.
			
			BackgroundTransfer[] transfers;
			synchronized(backgroundTransfers) {
				transfers = new BackgroundTransfer[backgroundTransfers.size()];
				transfers = (BackgroundTransfer[]) backgroundTransfers.toArray(transfers);
			}
			
			// Wait for the outgoing transfers to complete.
			if(!waitForCompletedTransfers(transfers)) {
				synchronized(CHKInsertSender.this) {
					transferTimedOut = true; // probably, they disconnected
					return;
				}
			}
			
			long transfersCompletedTime = System.currentTimeMillis();
			
			// Wait for acknowledgements from each node, or timeouts.
			
			while(true) {
				
				synchronized(backgroundTransfers) {
					if(receiveFailed) return;
				}
				// First calculate the timeout
				int timeout;
				long now = System.currentTimeMillis();
				timeout = (int)Math.min(Integer.MAX_VALUE, (transfersCompletedTime + TRANSFER_COMPLETION_ACK_TIMEOUT) - now);
				if(timeout <= 0) {
					synchronized(CHKInsertSender.this) {
						Logger.error(this, "Timed out waiting for transfers to complete on "+uid);
						transferTimedOut = true;
						//CHKInsertSender.this.notifyAll();
					}
					return;
				}
				
				MessageFilter mf = null;
				
				//Build a message filter to capture acknowledgement messages from the nodes we are interested in.
				for(int i=0;i<transfers.length;i++) {
					BackgroundTransfer awc = transfers[i];
					// If disconnected, ignore.
					if(!awc.pn.isRoutable()) {
						Logger.normal(this, "Disconnected: "+awc.pn+" in "+CHKInsertSender.this);
						continue;
					}
					// If transfer failed, probably won't be acknowledged.
					if(!awc.transferSucceeded) {
						continue;
					}
					// Wait for completion.
					if(!awc.receivedCompletionNotice) {
						MessageFilter m =
							MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPInsertTransfersCompleted).setSource(awc.pn).setTimeout(timeout);
						if(mf == null)
							mf = m;
						else
							mf = m.or(mf);
						if(logMINOR) Logger.minor(this, "Waiting for "+awc.pn.getPeer());
					}
				}
				
				if (mf==null) {
					if (logMINOR) Logger.minor(this, "Done waiting, no more completion listeners");
					return;
				} else {
					Message m;
					try {
						m = node.usm.waitFor(mf, CHKInsertSender.this);
					} catch (DisconnectedException e) {
						// Which one? I have no idea.
						// Go around the loop again to find out.
						continue;
					}
					if(m == null) {
						Logger.error(this, "Timed out waiting for a final ack from any nodes.");
						//Would looping again help? We could either:
						// (1) loop and notice that there is no more time left (handling the timeout), or
						// (2) notice that the nodes we are waiting on are down and possibly exit gracefully (not implemented).
						continue;
					} else {
						// Process message
						PeerNode pn = (PeerNode) m.getSource();
						// pn cannot be null, because the filters will prevent garbage collection of the nodes
						boolean processed = false;
						for(int i=0;i<transfers.length;i++) {
							PeerNode p = transfers[i].pn;
							if(p == pn) {
								boolean anyTimedOut = m.getBoolean(DMT.ANY_TIMED_OUT);
								transfers[i].receivedNotice(!anyTimedOut);
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
					}
				}
			}
			} finally {
				synchronized(CHKInsertSender.this) {
					allTransfersCompleted = true;
					CHKInsertSender.this.notifyAll();
				}
			}
		}

		/** Block until all transfers have finished. @return True if there is any point in waiting for acknowledgements. */
		private boolean waitForCompletedTransfers(BackgroundTransfer[] transfers) {
			// MAYBE all done
			while(true) {
				boolean noneRouteable = true;
				boolean completedTransfers = true;
				for(int i=0;i<transfers.length;i++) {
					if(!transfers[i].pn.isRoutable()) continue;
					noneRouteable = false;
					if(!transfers[i].completedTransfer) {
						completedTransfers = false;
						break;
					}
				}
				if(completedTransfers) return true;
				if(noneRouteable) return false;

				synchronized(backgroundTransfers) {
					if(receiveFailed) return false;
					if(logMINOR) Logger.minor(this, "Waiting for completion");
					try {
						backgroundTransfers.wait(100*1000);
					} catch (InterruptedException e) {
						// Ignore
					}
				}				
			}
		}


	public synchronized boolean completed() {
		return allTransfersCompleted;
	}

	/** Block until status has been set to something other than NOT_FINISHED */
	public synchronized void waitForStatus() {
		while(status == NOT_FINISHED) {
			try {
				CHKInsertSender.this.wait(100*1000);
			} catch (InterruptedException e) {
				// Ignore
			}
		}
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

	public long getUID() {
		return uid;
	}

	private final Object totalBytesSync = new Object();
	private int totalBytesSent;
	
	public void sentBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesSent += x;
		}
	}
	
	public int getTotalSentBytes() {
		synchronized(totalBytesSync) {
			return totalBytesSent;
		}
	}
	
	private int totalBytesReceived;
	
	public void receivedBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesReceived += x;
		}
	}
	
	public int getTotalReceivedBytes() {
		synchronized(totalBytesSync) {
			return totalBytesReceived;
		}
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
	}

	public boolean failedReceive() {
		return receiveFailed;
	}

	public synchronized boolean startedSendingData() {
		return !backgroundTransfers.isEmpty();
	}
}
