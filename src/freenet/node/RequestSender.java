package freenet.node;

import java.io.IOException;
import java.util.HashSet;

import freenet.crypt.DSAPublicKey;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.RetrievalException;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyVerifyException;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.store.KeyCollisionException;
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
    final Key key;
    final double target;
    private short htl;
    final long uid;
    final Node node;
    private double nearestLoc;
    /** The source of this request if any - purely so we can avoid routing to it */
    final PeerNode source;
    private PartiallyReceivedBlock prb = null;
    private DSAPublicKey pubKey;
    private byte[] headers;
    private byte[] sskData;
    private boolean sentRequest;
    private SSKBlock block;
    
    // Terminal status
    // Always set finished AFTER setting the reason flag

    private int status = -1;
    static final int NOT_FINISHED = -1;
    static final int SUCCESS = 0;
    static final int ROUTE_NOT_FOUND = 1;
    static final int DATA_NOT_FOUND = 3;
    static final int TRANSFER_FAILED = 4;
    static final int VERIFY_FAILURE = 5;
    static final int TIMED_OUT = 6;
    static final int GENERATED_REJECTED_OVERLOAD = 7;
    static final int INTERNAL_ERROR = 8;
    
    
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public RequestSender(Key key, DSAPublicKey pubKey, short htl, long uid, Node n, double nearestLoc, 
            PeerNode source) {
        this.key = key;
        this.pubKey = pubKey;
        this.htl = htl;
        this.uid = uid;
        this.node = n;
        this.source = source;
        this.nearestLoc = nearestLoc;
        if(key instanceof NodeSSK && pubKey == null) {
        	pubKey = ((NodeSSK)key).getPubKey();
        	if(pubKey == null)
        		pubKey = node.getKey(((NodeSSK)key).getPubKeyHash());
        }
        
        target = key.toNormalizedDouble();
        Thread t = new Thread(this, "RequestSender for UID "+uid);
        t.setDaemon(true);
        t.start();
    }
    
    public void run() {
        short origHTL = htl;
        node.addSender(key, htl, this);
        HashSet nodesRoutedTo = new HashSet();
        HashSet nodesNotIgnored = new HashSet();
        try {
        while(true) {
            Logger.minor(this, "htl="+htl);
            if(htl == 0) {
                // RNF
                // Would be DNF if arrived with no HTL
                // But here we've already routed it and that's been rejected.
                finish(ROUTE_NOT_FOUND, null);
                return;
            }
            
            // Route it
            PeerNode next;
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
            Logger.minor(this, "Routing request to "+next);
            nodesRoutedTo.add(next);
            
            if(PeerManager.distance(target, nextValue) > PeerManager.distance(target, nearestLoc)) {
                htl = node.decrementHTL(source, htl);
                Logger.minor(this, "Backtracking: target="+target+" next="+nextValue+" closest="+nearestLoc+" so htl="+htl);
            }
            
            Message req = createDataRequest();
            
            
            next.send(req);
            sentRequest = true;
            
            Message msg = null;
            
            while(true) {
            	
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
                
                try {
                    msg = node.usm.waitFor(mf);
                    Logger.minor(this, "first part got "+msg);
                } catch (DisconnectedException e) {
                    Logger.normal(this, "Disconnected from "+next+" while waiting for Accepted on "+uid);
                    break;
                }
                
            	if(msg == null) {
            		Logger.minor(this, "Timeout waiting for Accepted");
            		// Timeout waiting for Accepted
            		next.localRejectedOverload("AcceptedTimeout");
            		forwardRejectedOverload();
            		// Try next node
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRejectedLoop) {
            		Logger.minor(this, "Rejected loop");
            		next.successNotOverload();
            		// Find another node to route to
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRejectedOverload) {
            		Logger.minor(this, "Rejected: overload");
					// Non-fatal - probably still have time left
					forwardRejectedOverload();
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						Logger.minor(this, "Is local");
						next.localRejectedOverload("ForwardRejectedOverload");
						Logger.minor(this, "Local RejectedOverload, moving on to next peer");
						// Give up on this one, try another
						break;
					}
					continue;
            	}
            	
            	if(msg.getSpec() != DMT.FNPAccepted) {
            		Logger.error(this, "Unrecognized message: "+msg);
            		continue;
            	}
            	
            	break;
            }
            
            if(msg == null || msg.getSpec() != DMT.FNPAccepted) {
            	// Try another node
            	continue;
            }

            Logger.minor(this, "Got Accepted");
            
            // Otherwise, must be Accepted
            
            // So wait...
            
            while(true) {
            	
                MessageFilter mfDNF = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPDataNotFound);
                MessageFilter mfDF = makeDataFoundFilter(next);
                MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPRouteNotFound);
                MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPRejectedOverload);
                MessageFilter mfPubKey = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPSSKPubKey);
            	MessageFilter mfRealDFCHK = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPCHKDataFound);
            	MessageFilter mfRealDFSSK = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPSSKDataFound);
                MessageFilter mf = mfDNF.or(mfRouteNotFound.or(mfRejectedOverload.or(mfDF.or(mfPubKey.or(mfRealDFCHK.or(mfRealDFSSK))))));

                
            	try {
            		msg = node.usm.waitFor(mf);
            	} catch (DisconnectedException e) {
            		Logger.normal(this, "Disconnected from "+next+" while waiting for data on "+uid);
            		continue;
            	}
            	
                Logger.minor(this, "second part got "+msg);
                
            	if(msg == null) {
            		// Fatal timeout
            		next.localRejectedOverload("FatalTimeout");
            		forwardRejectedOverload();
            		finish(TIMED_OUT, next);
            		return;
            	}
            	
            	if(msg.getSpec() == DMT.FNPDataNotFound) {
            		next.successNotOverload();
            		finish(DATA_NOT_FOUND, next);
            		return;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRouteNotFound) {
            		// Backtrack within available hops
            		short newHtl = msg.getShort(DMT.HTL);
            		if(newHtl < htl) htl = newHtl;
            		next.successNotOverload();
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRejectedOverload) {
					// Non-fatal - probably still have time left
					forwardRejectedOverload();
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						next.localRejectedOverload("ForwardRejectedOverload2");
						Logger.minor(this, "Local RejectedOverload, moving on to next peer");
						// Give up on this one, try another
						break;
					}
					continue; // Wait for any further response
            	}

            	if(msg.getSpec() == DMT.FNPCHKDataFound) {
            		if(!(key instanceof NodeCHK)) {
            			Logger.error(this, "Got "+msg+" but expected a different key type from "+next);
            			break;
            		}
            		
                	// Found data
                	next.successNotOverload();
                	
                	// First get headers
                	
                	headers = ((ShortBuffer)msg.getObject(DMT.BLOCK_HEADERS)).getData();
                	
                	// FIXME: Validate headers
                	
                	node.addTransferringSender((NodeCHK)key, this);
                	
                	try {
                		
                		prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
                		
                		synchronized(this) {
                			notifyAll();
                		}
                		
                		BlockReceiver br = new BlockReceiver(node.usm, next, uid, prb);
                		
                		try {
                			Logger.minor(this, "Receiving data");
                			byte[] data = br.receive();
                			Logger.minor(this, "Received data");
                			// Received data
                			CHKBlock block;
                			try {
                				verifyAndCommit(data);
                			} catch (KeyVerifyException e1) {
                				Logger.normal(this, "Got data but verify failed: "+e1, e1);
                				finish(VERIFY_FAILURE, next);
                				return;
                			}
                			finish(SUCCESS, next);
                			return;
                		} catch (RetrievalException e) {
                			Logger.normal(this, "Transfer failed: "+e, e);
                			finish(TRANSFER_FAILED, next);
                			return;
                		}
                	} finally {
                		node.removeTransferringSender((NodeCHK)key, this);
                	}
            	}
            	
            	if(msg.getSpec() == DMT.FNPSSKPubKey) {
            		
            		Logger.minor(this, "Got pubkey on "+uid);
            		
            		if(!(key instanceof NodeSSK)) {
            			Logger.error(this, "Got "+msg+" but expected a different key type from "+next);
            			break;
            		}
    				byte[] pubkeyAsBytes = ((ShortBuffer)msg.getObject(DMT.PUBKEY_AS_BYTES)).getData();
    				try {
    					if(pubKey == null)
    						pubKey = new DSAPublicKey(pubkeyAsBytes);
    					((NodeSSK)key).setPubKey(pubKey);
    				} catch (SSKVerifyException e) {
    					pubKey = null;
    					Logger.error(this, "Invalid pubkey from "+source+" on "+uid+" ("+e.getMessage()+")", e);
    					break; // try next node
    				} catch (IOException e) {
    					Logger.error(this, "Invalid pubkey from "+source+" on "+uid+" ("+e+")");
    					break; // try next node
    				}
    				if(sskData != null) {
    					finishSSK(next);
    					return;
    				}
    				continue;
            	}
            	
            	if(msg.getSpec() == DMT.FNPSSKDataFound) {

            		Logger.minor(this, "Got data on "+uid);
            		
            		if(!(key instanceof NodeSSK)) {
            			Logger.error(this, "Got "+msg+" but expected a different key type from "+next);
            			break;
            		}
            		
                	headers = ((ShortBuffer)msg.getObject(DMT.BLOCK_HEADERS)).getData();
            		
                	sskData = ((ShortBuffer)msg.getObject(DMT.DATA)).getData();
                	
                	if(pubKey != null) {
                		finishSSK(next);
                		return;
                	}
                	continue;
            	}
            	
           		Logger.error(this, "Unexpected message: "+msg);
            	
            }
        }
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
            finish(INTERNAL_ERROR, null);
        } finally {
        	Logger.minor(this, "Leaving RequestSender.run() for "+uid);
            node.completed(uid);
            node.removeSender(key, origHTL, this);
        }
    }

    private void finishSSK(PeerNode next) {
    	try {
			block = new SSKBlock(sskData, headers, (NodeSSK)key, false);
			node.store(block);
			finish(SUCCESS, next);
		} catch (SSKVerifyException e) {
			Logger.error(this, "Failed to verify: "+e+" from "+next, e);
			finish(VERIFY_FAILURE, next);
			return;
		} catch (KeyCollisionException e) {
			Logger.normal(this, "Collision on "+this);
		}
	}

    /**
     * Note that this must be first on the list.
     */
	private MessageFilter makeDataFoundFilter(PeerNode next) {
    	if(key instanceof NodeCHK)
    		return MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPCHKDataFound);
    	else if(key instanceof NodeSSK) {
    		return MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPSSKDataFound);
    	}
    	else throw new IllegalStateException("Unknown keytype: "+key);
	}

	private Message createDataRequest() {
    	if(key instanceof NodeCHK)
    		return DMT.createFNPCHKDataRequest(uid, htl, (NodeCHK)key, nearestLoc);
    	else if(key instanceof NodeSSK)
    		return DMT.createFNPSSKDataRequest(uid, htl, (NodeSSK)key, nearestLoc, pubKey == null);
    	else throw new IllegalStateException("Unknown keytype: "+key);
	}

	private void verifyAndCommit(byte[] data) throws KeyVerifyException {
    	if(key instanceof NodeCHK) {
    		CHKBlock block = new CHKBlock(data, headers, (NodeCHK)key);
    		node.store(block);
    	} else if (key instanceof NodeSSK) {
    		SSKBlock block = new SSKBlock(data, headers, (NodeSSK)key, false);
    		try {
				node.store(block);
			} catch (KeyCollisionException e) {
				Logger.normal(this, "Collision on "+this);
			}
    	}
	}

	private volatile boolean hasForwardedRejectedOverload;
    
    /** Forward RejectedOverload to the request originator */
    private synchronized void forwardRejectedOverload() {
    	if(hasForwardedRejectedOverload) return;
    	hasForwardedRejectedOverload = true;
   		notifyAll();
	}
    
    public PartiallyReceivedBlock getPRB() {
        return prb;
    }

    public boolean transferStarted() {
        return prb != null;
    }

    boolean hadROLastTimeWaited = false;
    boolean prbWasNonNull = false;
    
    /**
     * Wait until either the transfer has started or we have a 
     * terminal status code.
     * @return True if we got a RejectedOverload.
     */
    public synchronized boolean waitUntilStatusChange() {
        while(true) {
        	if((!hadROLastTimeWaited) && hasForwardedRejectedOverload) {
        		hadROLastTimeWaited = true;
        		return true;
        	}
        	if((!prbWasNonNull) && prb != null) {
        		prbWasNonNull = true;
        		return false;
        	}
            if(status != NOT_FINISHED) return false;
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
    
    private void finish(int code, PeerNode next) {
        Logger.minor(this, "finish("+code+")");
        if(status != NOT_FINISHED)
        	throw new IllegalStateException("finish() called with "+code+" when was already "+status);
        status = code;

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
    
    final byte[] getSSKData() {
    	return sskData;
    }
    
    public SSKBlock getSSKBlock() {
    	return block;
    }
}
