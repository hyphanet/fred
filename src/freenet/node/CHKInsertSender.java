/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashSet;
import java.util.Vector;

import freenet.io.comm.AsyncMessageFilterCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.xfer.AbortedException;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.NodeCHK;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

public final class CHKInsertSender implements PrioRunnable, AnyInsertSender, ByteCounter {
	
	private class BackgroundTransfer implements PrioRunnable, AsyncMessageFilterCallback {
		private final long uid;
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
			this.uid = CHKInsertSender.this.uid;
			bt = new BlockTransmitter(node.usm, pn, uid, prb, CHKInsertSender.this, BlockTransmitter.NEVER_CASCADE, realTimeFlag);
		}
		
		void start() {
			node.executor.execute(this, "CHKInsert-BackgroundTransfer for "+uid+" to "+pn.getPeer());
		}
		
		public void run() {
			freenet.support.Logger.OSThread.logPID(this);
			try {
				this.realRun();
			} catch (Throwable t) {
				this.completedTransfer(false);
				this.receivedNotice(false);
				Logger.error(this, "Caught "+t, t);
			}
		}
		
		private void realRun() {
			this.completedTransfer(bt.send(node.executor));
			// Double-check that the node is still connected. Pointless to wait otherwise.
			if (pn.isConnected() && transferSucceeded) {
				//synch-version: this.receivedNotice(waitForReceivedNotification(this));
				//Add ourselves as a listener for the longterm completion message of this transfer, then gracefully exit.
				try {
					node.usm.addAsyncFilter(getNotificationMessageFilter(), this);
				} catch (DisconnectedException e) {
					// Normal
					if(logMINOR)
						Logger.minor(this, "Disconnected while adding filter");
					this.completedTransfer(false);
					this.receivedNotice(false);
				}
			} else {
				this.receivedNotice(false);
				pn.localRejectedOverload("TransferFailedInsert");
			}
			// REDFLAG: Load limiting:
			// No confirmation that it has finished, and it won't finish immediately on the transfer finishing.
			// So don't try to thisTag.removeRoutingTo(next), just assume it keeps running until the whole insert finishes.
		}
		
		private void completedTransfer(boolean success) {
			synchronized(this) {
				transferSucceeded = success;
				completedTransfer = true;
				notifyAll();
			}
			synchronized(backgroundTransfers) {
				backgroundTransfers.notifyAll();
			}
			if(!success) {
				setTransferTimedOut();
			}
		}
		
		private void receivedNotice(boolean success) {
			synchronized(this) {
				if (receivedCompletionNotice) {
					Logger.error(this, "receivedNotice("+success+"), already had receivedNotice("+completionSucceeded+")");
				} else {
				completionSucceeded = success;
				receivedCompletionNotice = true;
				notifyAll();
				}
			}
			synchronized(backgroundTransfers) {
				backgroundTransfers.notifyAll();
			}
			if(!success) {
				setTransferTimedOut();
			}			
		}
		
		public void onMatched(Message m) {
			pn.successNotOverload();
			PeerNode pn = (PeerNode) m.getSource();
			// pn cannot be null, because the filters will prevent garbage collection of the nodes
			
			if(this.pn.equals(pn)) {
				boolean anyTimedOut = m.getBoolean(DMT.ANY_TIMED_OUT);
				if(anyTimedOut) {
					CHKInsertSender.this.setTransferTimedOut();
				}
				receivedNotice(!anyTimedOut);
			} else {
				Logger.error(this, "received completion notice for wrong node: "+pn+" != "+this.pn);
			}			
		}
		
		public boolean shouldTimeout() {
			//AFIACS, this will still let the filter timeout, but not call onMatched() twice.
			return receivedCompletionNotice;
		}
		
		private MessageFilter getNotificationMessageFilter() {
			return MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPInsertTransfersCompleted).setSource(pn).setTimeout(TRANSFER_COMPLETION_ACK_TIMEOUT);
		}

		public void onTimeout() {
			/* FIXME: Cascading timeout...
			   if this times out, we don't have any time to report to the node of origin the timeout notification (anyTimedOut?).
			 */
			// NORMAL priority because it is normally caused by a transfer taking too long downstream, and that doesn't usually indicate a bug.
			Logger.normal(this, "Timed out waiting for a final ack from: "+pn+" on "+this);
			pn.localRejectedOverload("InsertTimeoutNoFinalAck");
			receivedNotice(false);
		}

		public void onDisconnect(PeerContext ctx) {
			Logger.normal(this, "Disconnected "+ctx+" for "+this);
			receivedNotice(true); // as far as we know
		}

		public void onRestarted(PeerContext ctx) {
			Logger.normal(this, "Restarted "+ctx+" for "+this);
			receivedNotice(true);
		}

		public int getPriority() {
			return NativeThread.HIGH_PRIORITY;
		}
	}
	
	CHKInsertSender(NodeCHK myKey, long uid, InsertTag tag, byte[] headers, short htl, 
            PeerNode source, Node node, PartiallyReceivedBlock prb, boolean fromStore,
            boolean canWriteClientCache, boolean forkOnCacheable, boolean preferInsert, boolean ignoreLowBackoff, boolean realTimeFlag) {
        this.myKey = myKey;
        this.target = myKey.toNormalizedDouble();
        this.origUID = uid;
        this.uid = uid;
        this.origTag = tag;
        this.headers = headers;
        this.htl = htl;
        this.source = source;
        this.node = node;
        this.prb = prb;
        this.fromStore = fromStore;
        this.startTime = System.currentTimeMillis();
        this.backgroundTransfers = new Vector<BackgroundTransfer>();
        this.forkOnCacheable = forkOnCacheable;
        this.preferInsert = preferInsert;
        this.ignoreLowBackoff = ignoreLowBackoff;
        this.realTimeFlag = realTimeFlag;
        logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
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
    final long origUID;
    final InsertTag origTag;
    long uid;
    private InsertTag forkedRequestTag;
    short htl;
    final PeerNode source;
    final Node node;
    final byte[] headers; // received BEFORE creation => we handle Accepted elsewhere
    final PartiallyReceivedBlock prb;
    final boolean fromStore;
    private boolean receiveFailed;
    final long startTime;
    private boolean sentRequest;
    private final boolean forkOnCacheable;
    private final boolean preferInsert;
    private final boolean ignoreLowBackoff;
    private final boolean realTimeFlag;
    private HashSet<PeerNode> nodesRoutedTo = new HashSet<PeerNode>();

    
    /** List of nodes we are waiting for either a transfer completion
     * notice or a transfer completion from. Also used as a sync object for waiting for transfer completion. */
    private Vector<BackgroundTransfer> backgroundTransfers;
    
    /** Have all transfers completed and all nodes reported completion status? */
    private boolean allTransfersCompleted;
    
    /** Has a transfer timed out, either directly or downstream? */
    private volatile boolean transferTimedOut;
    
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
    
    @Override
	public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
        short origHTL;
    	synchronized (this) {
            origHTL = htl;
		}

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
        	if(forkedRequestTag != null)
            	node.unlockUID(uid, false, true, false, false, false, realTimeFlag, forkedRequestTag);
        }
    }
    
	static final int MAX_HIGH_HTL_FAILURES = 5;
	
    private void realRun() {
        
        PeerNode next = null;
        // While in no-cache mode, we don't decrement HTL on a RejectedLoop or similar, but we only allow a limited number of such failures before RNFing.
        int highHTLFailureCount = 0;
        boolean starting = true;
        while(true) {
        	synchronized(backgroundTransfers) {
        		if(receiveFailed) {
        			return; // don't need to set status as killed by CHKInsertHandler
        		}
        	}
            
            /*
             * If we haven't routed to any node yet, decrement according to the source.
             * If we have, decrement according to the node which just failed.
             * Because:
             * 1) If we always decrement according to source then we can be at max or min HTL
             * for a long time while we visit *every* peer node. This is BAD!
             * 2) The node which just failed can be seen as the requestor for our purposes.
             */
            // Decrement at this point so we can DNF immediately on reaching HTL 0.
            boolean canWriteStorePrev = node.canWriteDatastoreInsert(htl);
            if((!starting) && (!canWriteStorePrev)) {
            	// We always decrement on starting a sender.
            	// However, after that, if our HTL is above the no-cache threshold,
            	// we do not want to decrement the HTL for trivial rejections (e.g. RejectedLoop),
            	// because we would end up caching data too close to the originator.
            	// So allow 5 failures and then RNF.
            	if(highHTLFailureCount++ >= MAX_HIGH_HTL_FAILURES) {
            		if(logMINOR) Logger.minor(this, "Too many failures at non-cacheable HTL");
                    finish(ROUTE_NOT_FOUND, null);
                    return;
            	}
            	if(logMINOR) Logger.minor(this, "Allowing failure "+highHTLFailureCount+" htl is still "+htl);
            } else {
                htl = node.decrementHTL(sentRequest ? next : source, htl);
                if(logMINOR) Logger.minor(this, "Decremented HTL to "+htl);
            }
            starting = false;
            synchronized (this) {
            	if(htl == 0) {
            		// Send an InsertReply back
            		if(!sentRequest)
            			origTag.setNotRoutedOnwards();
            		finish(SUCCESS, null);
            		return;
            	}
            }
            
            if( node.canWriteDatastoreInsert(htl) && (!canWriteStorePrev) && forkOnCacheable) {
            	// FORK! We are now cacheable, and it is quite possible that we have already gone over the ideal sink nodes,
            	// in which case if we don't fork we will miss them, and greatly reduce the insert's reachability.
            	// So we fork: Create a new UID so we can go over the previous hops again if they happen to be good places to store the data.
            	
            	// Existing transfers will keep their existing UIDs, since they copied the UID in the constructor.
            	
            	forkedRequestTag = new InsertTag(false, InsertTag.START.REMOTE, source, realTimeFlag);
            	uid = node.random.nextLong();
            	Logger.normal(this, "FORKING CHK INSERT "+origUID+" to "+uid);
            	nodesRoutedTo.clear();
            	node.lockUID(uid, false, true, false, false, realTimeFlag, forkedRequestTag);
            }
            
            // Route it
            // Can backtrack, so only route to nodes closer than we are to target.
            next = node.peers.closerPeer(forkedRequestTag == null ? source : null, nodesRoutedTo, target, true, node.isAdvancedModeEnabled(), -1, null,
			        null, htl, ignoreLowBackoff ? Node.LOW_BACKOFF : 0, source == null);
			
            if(next == null) {
                // Backtrack
        		if(!sentRequest)
        			origTag.setNotRoutedOnwards();
                finish(ROUTE_NOT_FOUND, null);
                return;
            }
			
            if(logMINOR) Logger.minor(this, "Routing insert to "+next);
            nodesRoutedTo.add(next);
            
            Message req;
            
            req = DMT.createFNPInsertRequest(uid, htl, myKey);
            if(forkOnCacheable != Node.FORK_ON_CACHEABLE_DEFAULT) {
            	req.addSubMessage(DMT.createFNPSubInsertForkControl(forkOnCacheable));
            }
            if(ignoreLowBackoff != Node.IGNORE_LOW_BACKOFF_DEFAULT) {
            	req.addSubMessage(DMT.createFNPSubInsertIgnoreLowBackoff(ignoreLowBackoff));
            }
            if(preferInsert != Node.PREFER_INSERT_DEFAULT) {
            	req.addSubMessage(DMT.createFNPSubInsertPreferInsert(preferInsert));
            }
        	req.addSubMessage(DMT.createFNPRealTimeFlag(realTimeFlag));
            
            // Wait for ack or reject... will come before even a locally generated DataReply
            
            MessageFilter mfAccepted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
            MessageFilter mfRejectedLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedLoop);
            MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedOverload);
            
            // mfRejectedOverload must be the last thing in the or
            // So its or pointer remains null
            // Otherwise we need to recreate it below
            mfRejectedOverload.clearOr();
            MessageFilter mf = mfAccepted.or(mfRejectedLoop.or(mfRejectedOverload));
            
            InsertTag thisTag = forkedRequestTag;
            if(forkedRequestTag == null) thisTag = origTag;
            
            thisTag.addRoutedTo(next, false);
            
            // Send to next node
            
            try {
				/*
				 When using sendSync(), this send can often timeout (it is the first request we are sending to this node).
				 -If sendSync blocks here (message queue is full, node down, etc.) it can take up to 10 minutes,
				  if this occurs at even two nodes in any given insert (at any point in the path), the entire insert chain
				  will fatally timeout.
				 -We cannot be informed if sendSync() does timeout. A message will be logged, but this thread will simply continue
				   to the waitFor() and spend another timeout period there.
				 -The timeout on the waitFor() is 10 seconds (ACCEPTED_TIMEOUT).
				 -The interesting case is when this next node is temporarily busy, in which case we might skip a busy node if they
				   don't respond in ten seconds (ACCEPTED_TIMEOUT). Or, if the length of the send queue to them is greater than
				   ACCEPTED_TIMEOUT, using sendAsync() will skip them before they get the request. This would be a need for retuning
				   ACCEPTED_TIMEOUT.
				 */
				next.sendAsync(req, null, this);
			} catch (NotConnectedException e1) {
				if(logMINOR) Logger.minor(this, "Not connected to "+next);
				next.noLongerRoutingTo(thisTag, false);
				continue;
			}
			synchronized (this) {
				sentRequest = true;				
			}
            
			synchronized(backgroundTransfers) {
				if(receiveFailed) return; // don't need to set status as killed by CHKInsertHandler
			}
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
					next.noLongerRoutingTo(thisTag, false);
					break;
				}
				
				synchronized(backgroundTransfers) {
					if (receiveFailed)
						return; // don't need to set status as killed by CHKInsertHandler
				}
				
				if (msg == null) {
					// Terminal overload
					// Try to propagate back to source
					if(logMINOR) Logger.minor(this, "Timeout");
					next.localRejectedOverload("Timeout3");
					// Try another node.
					forwardRejectedOverload();
					// It could still be running. So the timeout is fatal to the node.
        			Logger.error(this, "Timeout awaiting Accepted/Rejected "+this+" to "+next);
        			next.fatalTimeout();
					break;
				}
				
				if (msg.getSpec() == DMT.FNPRejectedOverload) {
					// Non-fatal - probably still have time left
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						next.localRejectedOverload("ForwardRejectedOverload5");
						if(logMINOR) Logger.minor(this,
										"Local RejectedOverload, moving on to next peer");
						// Give up on this one, try another
						next.noLongerRoutingTo(thisTag, false);
						break;
					} else {
						forwardRejectedOverload();
					}
					continue;
				}
				
				if (msg.getSpec() == DMT.FNPRejectedLoop) {
					next.successNotOverload();
					// Loop - we don't want to send the data to this one
					next.noLongerRoutingTo(thisTag, false);
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
            synchronized(backgroundTransfers) {
            	if(receiveFailed) return;
            }
            try {
				next.sendSync(dataInsert, this);
			} catch (NotConnectedException e1) {
				if(logMINOR) Logger.minor(this, "Not connected sending DataInsert: "+next+" for "+uid);
				continue;
			}

			if(logMINOR) Logger.minor(this, "Sending data");
			startBackgroundTransfer(next, prb);
			
            while (true) {

            	synchronized(backgroundTransfers) {
            		if (receiveFailed)
            			return;
            	}
				
				try {
					msg = node.usm.waitFor(mf, this);
				} catch (DisconnectedException e) {
					Logger.normal(this, "Disconnected from " + next
							+ " while waiting for InsertReply on " + this);
					break;
				}
				synchronized(backgroundTransfers) {
					if (receiveFailed)
						return;
				}
				
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
					//RNF means that the HTL was not exhausted, but that the data will still be stored.
					break;
				}

				//Can occur after reception of the entire chk block
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
						boolean recvFailed;
						synchronized(backgroundTransfers) {
							recvFailed = receiveFailed;
						}
						if (recvFailed) {
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
				} else {
					// Our task is complete, one node (quite deep), has accepted the insert.
					// The request will not be routed to any other nodes, this is where the data *should* be.
					finish(SUCCESS, next);
					return;
				}
			}
			if (logMINOR) Logger.debug(this, "Trying alternate node for insert");
		}
	}

	private void startBackgroundTransfer(PeerNode node, PartiallyReceivedBlock prb) {
		BackgroundTransfer ac = new BackgroundTransfer(node, prb);
		synchronized(backgroundTransfers) {
			backgroundTransfers.add(ac);
			backgroundTransfers.notifyAll();
		}
		ac.start();
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
	
	private void setTransferTimedOut() {
		if (transferTimedOut) return;
		synchronized(this) {
			if(!transferTimedOut) {
				transferTimedOut = true;
				notifyAll();
			}
		}
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
		
        boolean failedRecv; // receiveFailed is protected by backgroundTransfers but status by this
        // Now wait for transfers, or for downstream transfer notifications.
        // Note that even the data receive may not have completed by this point.
		synchronized(backgroundTransfers) {
			if (!backgroundTransfers.isEmpty()) {
				waitForBackgroundTransferCompletions();
			} else {
				if(logMINOR) Logger.minor(this, "No background transfers");
			}
			failedRecv = receiveFailed;
		}
        
        	synchronized(this) {
        		if(failedRecv)
        			status = RECEIVE_FAILED;
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
     * Called by CHKInsertHandler to notify that the receive has
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
		
		private void waitForBackgroundTransferCompletions() {
			try {
		    freenet.support.Logger.OSThread.logPID(this);
			if(logMINOR) Logger.minor(this, "Waiting for background transfer completions: "+this);
			
			// We must presently be at such a stage that no more background transfers will be added.
			
			BackgroundTransfer[] transfers;
			synchronized(backgroundTransfers) {
				transfers = new BackgroundTransfer[backgroundTransfers.size()];
				transfers = backgroundTransfers.toArray(transfers);
			}
			
			// Wait for the outgoing transfers to complete.
			if(!waitForBackgroundTransfers(transfers)) {
				setTransferTimedOut();
				return;
			}
			} finally {
				synchronized(CHKInsertSender.this) {
					allTransfersCompleted = true;
					CHKInsertSender.this.notifyAll();
				}
			}
		}

		/**
		 * Block until all transfers have reached a final-terminal state (success/failure). On success this means that a
		 * successful 'received-notification' has been received.
		 * @return True if all background transfers were successful.
		 */
		private boolean waitForBackgroundTransfers(BackgroundTransfer[] transfers) {
			long start = System.currentTimeMillis();
			// Generous deadline so we catch bugs more obviously
			long deadline = start + TRANSFER_COMPLETION_ACK_TIMEOUT * 3;
			// MAYBE all done
			while(true) {
				if(System.currentTimeMillis() > deadline) {
					// NORMAL priority because it is normally caused by a transfer taking too long downstream, and that doesn't usually indicate a bug.
					Logger.normal(this, "Timed out waiting for background transfers! Probably caused by async filter not getting a timeout notification! DEBUG ME!");
					return false;
				}
				//If we want to be sure to exit as-soon-as the transfers are done, then we must hold the lock while we check.
				synchronized(backgroundTransfers) {
					if(receiveFailed) return false;
					
					boolean noneRouteable = true;
					boolean completedTransfers = true;
					boolean completedNotifications = true;
					for(int i=0;i<transfers.length;i++) {
						if(!transfers[i].pn.isRoutable()) continue;
						noneRouteable = false;
						if(!transfers[i].completedTransfer) {
							if(logMINOR)
								Logger.minor(this, "Waiting for transfer completion to "+transfers[i].pn+" : "+transfers[i]);
							//must wait
							completedTransfers = false;
							break;
						}
						if (!transfers[i].receivedCompletionNotice) {
							if(logMINOR)
								Logger.minor(this, "Waiting for completion notice from "+transfers[i].pn+" : "+transfers[i]);
							//must wait
							completedNotifications = false;
							break;
						}
						if (!transfers[i].completionSucceeded)
							return false;
					}
					if(noneRouteable) return false;
					if(completedTransfers && completedNotifications) return true;
					
					if(logMINOR) Logger.minor(this, "Waiting: transfer completion=" + completedTransfers + " notification="+completedNotifications); 
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
		node.nodeStats.insertSentBytes(false, x);
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
		node.nodeStats.insertReceivedBytes(false, x);
	}
	
	public int getTotalReceivedBytes() {
		synchronized(totalBytesSync) {
			return totalBytesReceived;
		}
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
		node.nodeStats.insertSentBytes(false, -x);
	}

	public boolean failedReceive() {
		return receiveFailed;
	}

	public synchronized boolean startedSendingData() {
		return !backgroundTransfers.isEmpty();
	}

	public int getPriority() {
		return NativeThread.HIGH_PRIORITY;
	}

	public PeerNode[] getRoutedTo() {
		return this.nodesRoutedTo.toArray(new PeerNode[nodesRoutedTo.size()]);
	}
}
