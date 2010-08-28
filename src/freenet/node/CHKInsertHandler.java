/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.RetrievalException;
import freenet.io.xfer.AbortedException;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.NodeCHK;
import freenet.store.KeyCollisionException;
import freenet.support.HexUtil;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.ShortBuffer;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * @author amphibian
 * 
 * Handle an incoming insert request.
 * This corresponds to RequestHandler.
 */
public class CHKInsertHandler implements PrioRunnable, ByteCounter {
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

    static final int DATA_INSERT_TIMEOUT = 10000;
    
    final Message req;
    final Node node;
    final long uid;
    final PeerNode source;
    final NodeCHK key;
    final long startTime;
    private short htl;
    private CHKInsertSender sender;
    private byte[] headers;
    private BlockReceiver br;
    private Thread runThread;
    PartiallyReceivedBlock prb;
    final InsertTag tag;
    private boolean canWriteDatastore;
	private final boolean forkOnCacheable;
	private final boolean preferInsert;
	private final boolean ignoreLowBackoff;
    
    CHKInsertHandler(Message req, PeerNode source, long id, Node node, long startTime, InsertTag tag, boolean forkOnCacheable, boolean preferInsert, boolean ignoreLowBackoff) {
        this.req = req;
        this.node = node;
        this.uid = id;
        this.source = source;
        this.startTime = startTime;
        this.tag = tag;
        key = (NodeCHK) req.getObject(DMT.FREENET_ROUTING_KEY);
        htl = req.getShort(DMT.HTL);
        if(htl <= 0) htl = 1;
        canWriteDatastore = node.canWriteDatastoreInsert(htl);
        receivedBytes(req.receivedByteCount());
        this.forkOnCacheable = forkOnCacheable;
        this.preferInsert = preferInsert;
        this.ignoreLowBackoff = ignoreLowBackoff;
    }
    
    @Override
	public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
        try {
        	realRun();
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
			tag.handlerThrew(e);
        } catch (Throwable t) {
            Logger.error(this, "Caught in run() "+t, t);
            tag.handlerThrew(t);
        } finally {
        	if(logMINOR) Logger.minor(this, "Exiting CHKInsertHandler.run() for "+uid);
            node.unlockUID(uid, false, true, false, false, false, tag);
        }
    }

    private void realRun() {
        runThread = Thread.currentThread();
        
        // FIXME implement rate limiting or something!
        // Send Accepted
        Message accepted = DMT.createFNPAccepted(uid);
        try {
			//Using sendSync here will help the next message filter not timeout... wait here or at the message filter.
			source.sendSync(accepted, this);
		} catch (NotConnectedException e1) {
			if(logMINOR) Logger.minor(this, "Lost connection to source");
			return;
		}
        
        // Source will send us a DataInsert
        
        MessageFilter mf;
        mf = MessageFilter.create().setType(DMT.FNPDataInsert).setField(DMT.UID, uid).setSource(source).setTimeout(DATA_INSERT_TIMEOUT);
        
        Message msg;
        try {
            msg = node.usm.waitFor(mf, this);
        } catch (DisconnectedException e) {
            Logger.normal(this, "Disconnected while waiting for DataInsert on "+uid);
            return;
        }
        
        if(logMINOR) Logger.minor(this, "Received "+msg);
        
        if(msg == null) {
        	try {
        		if(source.isConnected() && (startTime > (source.timeLastConnectionCompleted()+Node.HANDSHAKE_TIMEOUT*4)))
        			Logger.error(this, "Did not receive DataInsert on "+uid+" from "+source+" !");
        		Message tooSlow = DMT.createFNPRejectedTimeout(uid);
        		source.sendAsync(tooSlow, null, this);
        		Message m = DMT.createFNPInsertTransfersCompleted(uid, true);
        		source.sendAsync(m, null, this);
        		prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
        		br = new BlockReceiver(node.usm, source, uid, prb, this, node.getTicker(), false);
        		prb.abort(RetrievalException.NO_DATAINSERT, "No DataInsert");
        		br.sendAborted(RetrievalException.NO_DATAINSERT, "No DataInsert");
        		return;
        	} catch (NotConnectedException e) {
        		if(logMINOR) Logger.minor(this, "Lost connection to source");
    			return;
        	}
        }
        
        // We have a DataInsert
        headers = ((ShortBuffer)msg.getObject(DMT.BLOCK_HEADERS)).getData();
        // FIXME check the headers
        
        // Now create an CHKInsertSender, or use an existing one, or
        // discover that the data is in the store.

        // From this point onwards, if we return cleanly we must go through finish().
        
        prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
        if(htl > 0)
            sender = node.makeInsertSender(key, htl, uid, tag, source, headers, prb, false, false, forkOnCacheable, preferInsert, ignoreLowBackoff);
        br = new BlockReceiver(node.usm, source, uid, prb, this, node.getTicker(), false);
        
        // Receive the data, off thread
        Runnable dataReceiver = new DataReceiver();
		receiveStarted = true;
        node.executor.execute(dataReceiver, "CHKInsertHandler$DataReceiver for UID "+uid);

        if(htl == 0) {
            canCommit = true;
        	msg = DMT.createFNPInsertReply(uid);
        	try {
				source.sendSync(msg, this);
			} catch (NotConnectedException e) {
				// Ignore
			}
            finish(CHKInsertSender.SUCCESS);
            return;
        }
        
        // Wait...
        // What do we want to wait for?
        // If the data receive completes, that's very nice,
        // but doesn't really matter. What matters is what
        // happens to the CHKInsertSender. If the data receive
        // fails, that does matter...
        
        // We are waiting for a terminal status on the CHKInsertSender,
        // including SUCCESS.
        // If we get transfer failed, we can check whether the receive
        // failed first. If it did it's not our fault.
        // If the receive failed, and we haven't started transferring
        // yet, we probably want to kill the sender.
        // So we call the wait method on the CHKInsertSender, but we
        // also have a flag locally to indicate the receive failed.
        // And if it does, we interrupt.
        
        boolean receivedRejectedOverload = false;
        
        while(true) {
            synchronized(sender) {
                try {
                	if(sender.getStatus() == CHKInsertSender.NOT_FINISHED)
                		sender.wait(5000);
                } catch (InterruptedException e) {
                    // Cool, probably this is because the receive failed...
                }
            }
            if(receiveFailed()) {
                // Nothing else we can do
                finish(CHKInsertSender.RECEIVE_FAILED);
                return;
            }
            
            if((!receivedRejectedOverload) && sender.receivedRejectedOverload()) {
            	receivedRejectedOverload = true;
            	// Forward it
            	Message m = DMT.createFNPRejectedOverload(uid, false);
            	try {
					source.sendSync(m, this);
				} catch (NotConnectedException e) {
					if(logMINOR) Logger.minor(this, "Lost connection to source");
					return;
				}
            }
            
            int status = sender.getStatus();
            
            if(status == CHKInsertSender.NOT_FINISHED) {
                continue;
            }

            // Local RejectedOverload's (fatal).
            // Internal error counts as overload. It'd only create a timeout otherwise, which is the same thing anyway.
            if((status == CHKInsertSender.TIMED_OUT) ||
            		(status == CHKInsertSender.GENERATED_REJECTED_OVERLOAD) ||
            		(status == CHKInsertSender.INTERNAL_ERROR)) {
                msg = DMT.createFNPRejectedOverload(uid, true);
                try {
					source.sendSync(msg, this);
				} catch (NotConnectedException e) {
					if(logMINOR) Logger.minor(this, "Lost connection to source");
					return;
				}
                // Might as well store it anyway.
                if((status == CHKInsertSender.TIMED_OUT) ||
                		(status == CHKInsertSender.GENERATED_REJECTED_OVERLOAD))
                	canCommit = true;
                finish(status);
                return;
            }
            
            if((status == CHKInsertSender.ROUTE_NOT_FOUND) || (status == CHKInsertSender.ROUTE_REALLY_NOT_FOUND)) {
                msg = DMT.createFNPRouteNotFound(uid, sender.getHTL());
                try {
					source.sendSync(msg, this);
				} catch (NotConnectedException e) {
					if(logMINOR) Logger.minor(this, "Lost connection to source");
					return;
				}
                canCommit = true;
                finish(status);
                return;
            }
            
            if(status == CHKInsertSender.RECEIVE_FAILED) {
            	// Probably source's fault.
            	finish(status);
            	return;
            }
            
            if(status == CHKInsertSender.SUCCESS) {
            	msg = DMT.createFNPInsertReply(uid);
            	try {
					source.sendSync(msg, this);
				} catch (NotConnectedException e) {
					Logger.minor(this, "Lost connection to source");
					return;
				}
                canCommit = true;
                finish(status);
                return;
            }
            
            // Otherwise...?
            Logger.error(this, "Unknown status code: "+sender.getStatusString());
            msg = DMT.createFNPRejectedOverload(uid, true);
            try {
				source.sendSync(msg, this);
			} catch (NotConnectedException e) {
				// Ignore
			}
            finish(CHKInsertSender.INTERNAL_ERROR);
            return;
        }
	}

	private boolean canCommit = false;
    private boolean sentCompletion = false;
    private Object sentCompletionLock = new Object();
    
    /**
     * If canCommit, and we have received all the data, and it
     * verifies, then commit it.
     */
    private void finish(int code) {
    	if(logMINOR) Logger.minor(this, "Waiting for receive");
		synchronized(this) {
			while(receiveStarted && !receiveCompleted) {
				try {
					wait(100*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
    	}
		
		CHKBlock block = verify();
		// If we wanted to reduce latency at the cost of security (bug 3338), we'd commit here, or even on the receiver thread.
		
        if(logMINOR) Logger.minor(this, "Waiting for completion");
        // Wait for completion
        boolean sentCompletionWasSet;
        synchronized(sentCompletionLock) {
        	sentCompletionWasSet = sentCompletion;
        	sentCompletion = true;
        }
        
		Message m=null;
		
        if((sender != null) && (!sentCompletionWasSet)) {
			//If there are downstream senders, our final success report depends on there being no timeouts in the chain.
        	while(true) {
        		synchronized(sender) {
        			if(sender.completed()) {
        				break;
        			}
        			try {
        				sender.wait(10*1000);
        			} catch (InterruptedException e) {
        				// Loop
        			}
        		}
        	}
        	boolean failed = sender.anyTransfersFailed();
        	m = DMT.createFNPInsertTransfersCompleted(uid, failed);
		}
		
		if((sender == null) && (!sentCompletionWasSet) && (canCommit)) {
			//There are no downstream senders, but we stored the data locally, report successful transfer.
			//Note that this is done even if the verify fails.
			m = DMT.createFNPInsertTransfersCompleted(uid, false /* no timeouts */);
		}		
		
		// Don't commit until after we have received all the downstream transfer completion notifications.
		// We don't want an attacker to see a ULPR notice from the inserter before he sees it from the end of the chain (bug 3338).
		if(block != null) {
			commit(block);
			block = null;
		}
        
        	try {
        		source.sendSync(m, this);
        		if(logMINOR) Logger.minor(this, "Sent completion: "+m+" for "+this);
        	} catch (NotConnectedException e1) {
        		if(logMINOR) Logger.minor(this, "Not connected: "+source+" for "+this);
        		// May need to commit anyway...
        	}
		        
        if(code != CHKInsertSender.TIMED_OUT && code != CHKInsertSender.GENERATED_REJECTED_OVERLOAD && 
        		code != CHKInsertSender.INTERNAL_ERROR && code != CHKInsertSender.ROUTE_REALLY_NOT_FOUND &&
        		code != CHKInsertSender.RECEIVE_FAILED && !receiveFailed()) {
        	int totalSent = getTotalSentBytes();
        	int totalReceived = getTotalReceivedBytes();
        	if(sender != null) {
        		totalSent += sender.getTotalSentBytes();
        		totalReceived += sender.getTotalReceivedBytes();
        	}
        	if(logMINOR) Logger.minor(this, "Remote CHK insert cost "+totalSent+ '/' +totalReceived+" bytes ("+code+ ") receive failed = "+receiveFailed());
        	node.nodeStats.remoteChkInsertBytesSentAverage.report(totalSent);
        	node.nodeStats.remoteChkInsertBytesReceivedAverage.report(totalReceived);
        	if(code == CHKInsertSender.SUCCESS) {
        		// Report both sent and received because we have both a Handler and a Sender
        		if(sender != null && sender.startedSendingData())
        			node.nodeStats.successfulChkInsertBytesSentAverage.report(totalSent);
        		node.nodeStats.successfulChkInsertBytesReceivedAverage.report(totalReceived);
        	}
        }
    }
    
    /**
     * Verify data, or send DataInsertRejected.
     */
    private CHKBlock verify() {
        Message toSend = null;
        
        CHKBlock block = null;
        
        synchronized(this) {
        	if((prb == null) || prb.isAborted()) return null;
            try {
                if(!canCommit) return null;
                if(!prb.allReceived()) return null;
                block = new CHKBlock(prb.getBlock(), headers, key);
            } catch (CHKVerifyException e) {
            	Logger.error(this, "Verify failed in CHKInsertHandler: "+e+" - headers: "+HexUtil.bytesToHex(headers), e);
                toSend = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_VERIFY_FAILED);
            } catch (AbortedException e) {
            	Logger.error(this, "Receive failed: "+e);
            	// Receiver thread (below) will handle sending the failure notice
            }
        }
        if(toSend != null) {
            try {
                source.sendAsync(toSend, null, this);
            } catch (NotConnectedException e) {
                // :(
            	if(logMINOR) Logger.minor(this, "Lost connection in "+this+" when sending FNPDataInsertRejected");
            }
        }
        return block;
	}
    
    private void commit(CHKBlock block) {
        try {
			node.store(block, node.shouldStoreDeep(key, source, sender == null ? new PeerNode[0] : sender.getRoutedTo()), false, canWriteDatastore, false);
		} catch (KeyCollisionException e) {
			// Impossible with CHKs.
		}
        if(logMINOR) Logger.minor(this, "Committed");
    }

	/** Has the receive failed? If so, there's not much more that can be done... */
    private boolean receiveFailed;
    
    private boolean receiveStarted;
    private boolean receiveCompleted;

    public class DataReceiver implements PrioRunnable {

        public void run() {
		    freenet.support.Logger.OSThread.logPID(this);
        	if(logMINOR) Logger.minor(this, "Receiving data for "+CHKInsertHandler.this);
            try {
            	// Don't log whether the transfer succeeded or failed as the transfer was initiated by the source therefore could be unreliable evidence.
                br.receive();
                if(logMINOR) Logger.minor(this, "Received data for "+CHKInsertHandler.this);
            	synchronized(CHKInsertHandler.this) {
            		receiveCompleted = true;
            		CHKInsertHandler.this.notifyAll();
            	}
            	node.nodeStats.successfulBlockReceive();
            } catch (RetrievalException e) {
            	synchronized(CHKInsertHandler.this) {
            		receiveCompleted = true;
            		receiveFailed = true;
            		CHKInsertHandler.this.notifyAll();
            	}
                // Cancel the sender
            	if(sender != null)
            		sender.receiveFailed(); // tell it to stop if it hasn't already failed... unless it's sending from store
                runThread.interrupt();
                Message msg = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_RECEIVE_FAILED);
                try {
                    source.sendSync(msg, CHKInsertHandler.this);
                } catch (NotConnectedException ex) {
					//If they are not connected, that's probably why the receive failed!
                    if (logMINOR) Logger.minor(this, "Can't send "+msg+" to "+source+": "+ex);
                }
				if (e.getReason()==RetrievalException.SENDER_DISCONNECTED)
					Logger.normal(this, "Failed to retrieve (disconnect): "+e, e);
				else
					// Annoying, but we have stats for this; no need to call attention to it, it's unlikely to be a bug.
					Logger.normal(this, "Failed to retrieve ("+e.getReason()+"/"+RetrievalException.getErrString(e.getReason())+"): "+e, e);
            	node.nodeStats.failedBlockReceive(false, false, false);
                return;
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t, t);
            }
        }

        @Override
		public String toString() {
        	return super.toString()+" for "+uid;
        }

		public int getPriority() {
			return NativeThread.HIGH_PRIORITY;
		}
        
    }

    private synchronized boolean receiveFailed() {
    	return receiveFailed;
    }
    
    private final Object totalSync = new Object();
    private int totalSentBytes;
    private int totalReceivedBytes;
    
	public void sentBytes(int x) {
		synchronized(totalSync) {
			totalSentBytes += x;
		}
		node.nodeStats.insertSentBytes(false, x);
	}

	public void receivedBytes(int x) {
		synchronized(totalSync) {
			totalReceivedBytes += x;
		}
		node.nodeStats.insertReceivedBytes(false, x);
	}

	public int getTotalSentBytes() {
		return totalSentBytes;
	}
	
	public int getTotalReceivedBytes() {
		return totalReceivedBytes;
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
		node.nodeStats.insertSentBytes(false, -x);
	}

	public int getPriority() {
		return NativeThread.HIGH_PRIORITY;
	}
}
