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
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.ShortBuffer;

/**
 * @author amphibian
 * 
 * Handle an incoming insert request.
 * This corresponds to RequestHandler.
 */
public class InsertHandler implements Runnable, ByteCounter {


    static final int DATA_INSERT_TIMEOUT = 10000;
    
    final Message req;
    final Node node;
    final long uid;
    final PeerNode source;
    final NodeCHK key;
    final long startTime;
    private double closestLoc;
    private short htl;
    private CHKInsertSender sender;
    private byte[] headers;
    private BlockReceiver br;
    private Thread runThread;
    PartiallyReceivedBlock prb;
    private static boolean logMINOR;
    
    InsertHandler(Message req, long id, Node node, long startTime) {
        this.req = req;
        this.node = node;
        this.uid = id;
        this.source = (PeerNode) req.getSource();
        this.startTime = startTime;
        key = (NodeCHK) req.getObject(DMT.FREENET_ROUTING_KEY);
        htl = req.getShort(DMT.HTL);
        closestLoc = req.getDouble(DMT.NEAREST_LOCATION);
        double targetLoc = key.toNormalizedDouble();
        double myLoc = node.lm.getLocation();
        if(Location.distance(targetLoc, myLoc) < Location.distance(targetLoc, closestLoc)) {
            closestLoc = myLoc;
            htl = node.maxHTL();
        }
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
        receivedBytes(req.receivedByteCount());
    }
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public void run() {
	    freenet.support.OSThread.logPID(this);
        try {
        	realRun();
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
        } catch (Throwable t) {
            Logger.error(this, "Caught in run() "+t, t);
        } finally {
        	if(logMINOR) Logger.minor(this, "Exiting InsertHandler.run() for "+uid);
            node.unlockUID(uid, false, true, false);
        }
    }

    private void realRun() {
        runThread = Thread.currentThread();
        
        // FIXME implement rate limiting or something!
        // Send Accepted
        Message accepted = DMT.createFNPAccepted(uid);
        try {
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
        		source.sendAsync(tooSlow, null, 0, this);
        		Message m = DMT.createFNPInsertTransfersCompleted(uid, true);
        		source.sendAsync(m, null, 0, this);
        		prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
        		br = new BlockReceiver(node.usm, source, uid, prb, this);
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
            sender = node.makeInsertSender(key, htl, uid, source, headers, prb, false, closestLoc, true);
        br = new BlockReceiver(node.usm, source, uid, prb, this);
        
        // Receive the data, off thread
        
        Runnable dataReceiver = new DataReceiver();
        node.executor.execute(dataReceiver, "InsertHandler$DataReceiver for UID "+uid);

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
        // including REPLIED_WITH_DATA.
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

//            // FIXME obviously! For debugging load issues.
//        	if(node.myName.equalsIgnoreCase("Toad #1") &&
//        			node.random.nextBoolean()) {
//        		// Maliciously timeout
//        		Logger.error(this, "Maliciously timing out: was "+sender.getStatusString());
//        		sentSuccess = true;
//        		return;
//        	}
        	
            // Local RejectedOverload's (fatal).
            // Internal error counts as overload. It'd only create a timeout otherwise, which is the same thing anyway.
            // We *really* need a good way to deal with nodes that constantly R_O!
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
    	if(logMINOR) Logger.minor(this, "Finishing");
        maybeCommit();
        
        if(logMINOR) Logger.minor(this, "Waiting for completion");
        // Wait for completion
        boolean sentCompletionWasSet;
        synchronized(sentCompletionLock) {
        	sentCompletionWasSet = sentCompletion;
        	sentCompletion = true;
        }
        
        if((sender != null) && (!sentCompletionWasSet)) {
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
        	Message m = DMT.createFNPInsertTransfersCompleted(uid, failed);
        	try {
        		source.sendSync(m, this);
        		if(logMINOR) Logger.minor(this, "Sent completion: "+failed+" for "+this);
        	} catch (NotConnectedException e1) {
        		if(logMINOR) Logger.minor(this, "Not connected: "+source+" for "+this);
        		// May need to commit anyway...
        	}
        }

    	synchronized(this) {
    		if(receiveStarted) {
    			while(!receiveCompleted) {
    				try {
						wait(100*1000);
					} catch (InterruptedException e) {
						// Ignore
					}
    			}
    		}
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
    private void maybeCommit() {
        Message toSend = null;
        
        synchronized(this) { // REDFLAG do not use synch(this) for any other purpose!
        	if((prb == null) || prb.isAborted()) return;
            try {
                if(!canCommit) return;
                if(!prb.allReceived()) return;
                CHKBlock block = new CHKBlock(prb.getBlock(), headers, key);
                node.store(block);
                if(logMINOR) Logger.minor(this, "Committed");
            } catch (CHKVerifyException e) {
            	Logger.error(this, "Verify failed in InsertHandler: "+e+" - headers: "+HexUtil.bytesToHex(headers), e);
                toSend = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_VERIFY_FAILED);
            } catch (AbortedException e) {
            	Logger.error(this, "Receive failed: "+e);
            	// Receiver thread will handle below
            }
        }
        if(toSend != null) {
            try {
                source.sendAsync(toSend, null, 0, this);
            } catch (NotConnectedException e) {
                // :(
            	if(logMINOR) Logger.minor(this, "Lost connection in "+this+" when sending FNPDataInsertRejected");
            }
        }
	}

	/** Has the receive failed? If so, there's not much more that can be done... */
    private boolean receiveFailed;
    
    private boolean receiveStarted;
    private boolean receiveCompleted;

    public class DataReceiver implements Runnable {

        public void run() {
		    freenet.support.OSThread.logPID(this);
        	synchronized(this) {
        		receiveStarted = true;
        	}
        	if(logMINOR) Logger.minor(this, "Receiving data for "+InsertHandler.this);
            try {
                br.receive();
                if(logMINOR) Logger.minor(this, "Received data for "+InsertHandler.this);
            	synchronized(InsertHandler.this) {
            		receiveCompleted = true;
            		InsertHandler.this.notifyAll();
            	}
                maybeCommit();
            } catch (RetrievalException e) {
            	synchronized(InsertHandler.this) {
            		receiveCompleted = true;
            		receiveFailed = true;
            		InsertHandler.this.notifyAll();
            	}
                // Cancel the sender
            	if(sender != null)
            		sender.receiveFailed(); // tell it to stop if it hasn't already failed... unless it's sending from store
                runThread.interrupt();
                Message msg = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_RECEIVE_FAILED);
                try {
                    source.sendSync(msg, InsertHandler.this);
                } catch (NotConnectedException ex) {
                    Logger.error(this, "Can't send "+msg+" to "+source+": "+ex);
                }
                if(logMINOR) Logger.minor(this, "Failed to retrieve: "+e, e);
                return;
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t, t);
            }
        }

        public String toString() {
        	return super.toString()+" for "+uid;
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
	}

	public void receivedBytes(int x) {
		synchronized(totalSync) {
			totalReceivedBytes += x;
		}
	}

	public int getTotalSentBytes() {
		return totalSentBytes;
	}
	
	public int getTotalReceivedBytes() {
		return totalReceivedBytes;
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
	}
}
