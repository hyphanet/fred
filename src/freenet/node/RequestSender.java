/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashSet;

import freenet.crypt.CryptFormatException;
import freenet.crypt.DSAPublicKey;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
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
import freenet.support.SimpleFieldSet;

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
public final class RequestSender implements Runnable, ByteCounter {

    // Constants
    static final int ACCEPTED_TIMEOUT = 5000;
    static final int FETCH_TIMEOUT = 120000;
    /** Wait up to this long to get a path folding reply */
    static final int OPENNET_TIMEOUT = 120000;
    /** One in this many successful requests is randomly reinserted.
     * This is probably a good idea anyway but with the split store it's essential. */
    static final int RANDOM_REINSERT_INTERVAL = 200;
    
    // Basics
    final Key key;
    final double target;
    final boolean resetNearestLoc;
    private short htl;
    final long uid;
    final Node node;
    private double nearestLoc;
    /** The source of this request if any - purely so we can avoid routing to it */
    final PeerNode source;
    private PartiallyReceivedBlock prb;
    private DSAPublicKey pubKey;
    private byte[] headers;
    private byte[] sskData;
    private SSKBlock block;
    private boolean hasForwarded;
    
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
    static final int RECENTLY_FAILED = 9;
    private PeerNode successFrom;
    
    private static boolean logMINOR;
    
    public String toString() {
        return super.toString()+" for "+uid;
    }

    /**
     * RequestSender constructor.
     * @param key The key to request. Its public key should have been looked up
     * already; RequestSender will not look it up.
     */
    public RequestSender(Key key, DSAPublicKey pubKey, short htl, long uid, Node n, double nearestLoc, boolean resetNearestLoc, 
            PeerNode source) {
        this.key = key;
        this.pubKey = pubKey;
        this.htl = htl;
        this.uid = uid;
        this.node = n;
        this.source = source;
        this.nearestLoc = nearestLoc;
        this.resetNearestLoc = resetNearestLoc;
        target = key.toNormalizedDouble();
        node.addRequestSender(key, htl, this);
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    }

    public void start() {
    	node.executor.execute(this, "RequestSender for UID "+uid);
    }
    
    public void run() {
        if((key instanceof NodeSSK) && (pubKey == null)) {
        	pubKey = ((NodeSSK)key).getPubKey();
        }
        
        short origHTL = htl;
        HashSet nodesRoutedTo = new HashSet();
        HashSet nodesNotIgnored = new HashSet();
        try {
        while(true) {
            if(logMINOR) Logger.minor(this, "htl="+htl);
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
            next = node.peers.closerPeer(source, nodesRoutedTo, nodesNotIgnored, target, true, node.isAdvancedModeEnabled(), -1, null);
            if(next != null)
                nextValue = next.getLocation();
            else
                nextValue = -1.0;
            
            if(next == null) {
                // Backtrack
                finish(ROUTE_NOT_FOUND, null);
                return;
            }
            if(logMINOR) Logger.minor(this, "Routing request to "+next);
            nodesRoutedTo.add(next);
            
            if(Location.distance(target, nextValue) > Location.distance(target, nearestLoc)) {
                htl = node.decrementHTL(source, htl);
                if(logMINOR) Logger.minor(this, "Backtracking: target="+target+" next="+nextValue+" closest="+nearestLoc+" so htl="+htl);
            }
            
            Message req = createDataRequest();
            
            // Not possible to get an accurate time for sending, guaranteed to be not later than the time of receipt.
            // Why? Because by the time the sent() callback gets called, it may already have been acked, under heavy load.
            // So take it from when we first started to try to send the request.
            // See comments below when handling FNPRecentlyFailed for why we need this.
            long timeSentRequest = System.currentTimeMillis();
            
            next.sendSync(req, this);
            
            synchronized(this) {
            	hasForwarded = true;
            }
            
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
                    msg = node.usm.waitFor(mf, this);
                    if(logMINOR) Logger.minor(this, "first part got "+msg);
                } catch (DisconnectedException e) {
                    Logger.normal(this, "Disconnected from "+next+" while waiting for Accepted on "+uid);
                    break;
                }
                
            	if(msg == null) {
            		if(logMINOR) Logger.minor(this, "Timeout waiting for Accepted");
            		// Timeout waiting for Accepted
            		next.localRejectedOverload("AcceptedTimeout");
            		forwardRejectedOverload();
            		// Try next node
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRejectedLoop) {
            		if(logMINOR) Logger.minor(this, "Rejected loop");
            		next.successNotOverload();
            		// Find another node to route to
            		break;
            	}
            	
            	if(msg.getSpec() == DMT.FNPRejectedOverload) {
            		if(logMINOR) Logger.minor(this, "Rejected: overload");
					// Non-fatal - probably still have time left
					forwardRejectedOverload();
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						if(logMINOR) Logger.minor(this, "Is local");
						next.localRejectedOverload("ForwardRejectedOverload");
						if(logMINOR) Logger.minor(this, "Local RejectedOverload, moving on to next peer");
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
            
            if((msg == null) || (msg.getSpec() != DMT.FNPAccepted)) {
            	// Try another node
            	continue;
            }

            if(logMINOR) Logger.minor(this, "Got Accepted");
            
            // Otherwise, must be Accepted
            
            // So wait...
            
            while(true) {
            	
                MessageFilter mfDNF = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPDataNotFound);
                MessageFilter mfRF = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPRecentlyFailed);
                MessageFilter mfDF = makeDataFoundFilter(next);
                MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPRouteNotFound);
                MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPRejectedOverload);
                MessageFilter mfPubKey = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPSSKPubKey);
            	MessageFilter mfRealDFCHK = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPCHKDataFound);
            	MessageFilter mfRealDFSSK = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(FETCH_TIMEOUT).setType(DMT.FNPSSKDataFound);
                MessageFilter mf = mfDNF.or(mfRF.or(mfRouteNotFound.or(mfRejectedOverload.or(mfDF.or(mfPubKey.or(mfRealDFCHK.or(mfRealDFSSK)))))));

                
            	try {
            		msg = node.usm.waitFor(mf, this);
            	} catch (DisconnectedException e) {
            		Logger.normal(this, "Disconnected from "+next+" while waiting for data on "+uid);
            		break;
            	}
            	
            	if(logMINOR) Logger.minor(this, "second part got "+msg);
                
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
            	
            	if(msg.getSpec() == DMT.FNPRecentlyFailed) {
            		next.successNotOverload();
            		/*
            		 * Must set a correct recentlyFailedTimeLeft before calling this finish(), because it will be
            		 * passed to the handler.
            		 * 
            		 * It is *VITAL* that the TIME_LEFT we pass on is not larger than it should be.
            		 * It is somewhat less important that it is not too much smaller than it should be.
            		 * 
            		 * Why? Because:
            		 * 1) We have to use FNPRecentlyFailed to create failure table entries. Because otherwise,
            		 * the failure table is of little value: A request is routed through a node, which gets a DNF,
            		 * and adds a failure table entry. Other requests then go through that node via other paths.
            		 * They are rejected with FNPRecentlyFailed - not with DataNotFound. If this does not create
            		 * failure table entries, more requests will be pointlessly routed through that chain.
            		 * 
            		 * 2) If we use a fixed timeout on receiving FNPRecentlyFailed, they can be self-seeding. 
            		 * What this means is A sends a request to B, which DNFs. This creates a failure table entry 
            		 * which lasts for 10 minutes. 5 minutes later, A sends another request to B, which is killed
            		 * with FNPRecentlyFailed because of the failure table entry. B's failure table lasts for 
            		 * another 5 minutes, but A's lasts for the full 10 minutes i.e. until 5 minutes after B's. 
            		 * After B's failure table entry has expired, but before A's expires, B sends a request to A. 
            		 * A replies with FNPRecentlyFailed. Repeat ad infinitum: A reinforces B's blocks, and B 
            		 * reinforces A's blocks!
            		 * 
            		 * 3) This can still happen even if we check where the request is coming from. A loop could 
            		 * very easily form: A - B - C - A. A requests from B, DNFs (assume the request comes in from 
            		 * outside, there are more nodes. C requests from A, sets up a block. B's block expires, C's 
            		 * is still active. A requests from B which requests from C ... and it goes round again.
            		 * 
            		 * 4) It is exactly the same if we specify a timeout, unless the timeout can be guaranteed to 
            		 * not increase the expiry time.
            		 */
            		
            		// First take the original TIME_LEFT. This will start at 10 minutes if we get rejected in
            		// the same millisecond as the failure table block was added.
            		int timeLeft = msg.getInt(DMT.TIME_LEFT);
            		int origTimeLeft = timeLeft;
            		
            		if(timeLeft <= 0) {
            			Logger.error(this, "Impossible: timeLeft="+timeLeft);
            			origTimeLeft = 0;
            			timeLeft=1000; // arbitrary default...
            		}
            		
            		// This is in theory relative to when the request was received by the node. Lets make it relative
            		// to a known event before that: the time when we sent the request.
            		
            		long timeSinceSent = Math.max(0, (System.currentTimeMillis() - timeSentRequest));
            		timeLeft -= timeSinceSent;
            		
            		// Subtract 1% for good measure / to compensate for dodgy clocks
            		timeLeft -= origTimeLeft / 100;
            		
            		if(timeLeft <= 0) {
            			// No timeout left, cool
            			finish(DATA_NOT_FOUND, next);
            		} else {
            			// Some timeout left
            			finish(RECENTLY_FAILED, next);
            		}
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
						if(logMINOR) Logger.minor(this, "Local RejectedOverload, moving on to next peer");
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
                		
                		BlockReceiver br = new BlockReceiver(node.usm, next, uid, prb, this);
                		
                		try {
                			if(logMINOR) Logger.minor(this, "Receiving data");
                			byte[] data = br.receive();
                			if(logMINOR) Logger.minor(this, "Received data");
                			// Received data
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
            		
            		if(logMINOR) Logger.minor(this, "Got pubkey on "+uid);
            		
            		if(!(key instanceof NodeSSK)) {
            			Logger.error(this, "Got "+msg+" but expected a different key type from "+next);
            			break;
            		}
    				byte[] pubkeyAsBytes = ((ShortBuffer)msg.getObject(DMT.PUBKEY_AS_BYTES)).getData();
    				try {
    					if(pubKey == null)
    						pubKey = DSAPublicKey.create(pubkeyAsBytes);
    					((NodeSSK)key).setPubKey(pubKey);
    				} catch (SSKVerifyException e) {
    					pubKey = null;
    					Logger.error(this, "Invalid pubkey from "+source+" on "+uid+" ("+e.getMessage()+ ')', e);
    					break; // try next node
    				} catch (CryptFormatException e) {
    					Logger.error(this, "Invalid pubkey from "+source+" on "+uid+" ("+e+ ')');
    					break; // try next node
    				}
    				if(sskData != null) {
    					finishSSK(next);
    					return;
    				}
    				continue;
            	}
            	
            	if(msg.getSpec() == DMT.FNPSSKDataFound) {

            		if(logMINOR) Logger.minor(this, "Got data on "+uid);
            		
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
        	if(logMINOR) Logger.minor(this, "Leaving RequestSender.run() for "+uid);
            node.removeRequestSender(key, origHTL, this);
        }
    }

    private void finishSSK(PeerNode next) {
    	try {
			block = new SSKBlock(sskData, headers, (NodeSSK)key, false);
			node.storeShallow(block);
			if(node.random.nextInt(RANDOM_REINSERT_INTERVAL) == 0)
				node.queueRandomReinsert(block);
			finish(SUCCESS, next);
		} catch (SSKVerifyException e) {
			Logger.error(this, "Failed to verify: "+e+" from "+next, e);
			finish(VERIFY_FAILURE, next);
			return;
		} catch (KeyCollisionException e) {
			Logger.normal(this, "Collision on "+this);
			finish(SUCCESS, next);
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
    		// Cache only in the cache, not the store. The reason for this is that
    		// requests don't go to the full distance, and therefore pollute the 
    		// store; simulations it is best to only include data from requests
    		// which go all the way i.e. inserts.
    		node.storeShallow(block);
			if(node.random.nextInt(RANDOM_REINSERT_INTERVAL) == 0)
				node.queueRandomReinsert(block);
    	} else if (key instanceof NodeSSK) {
    		try {
				node.storeShallow(new SSKBlock(data, headers, (NodeSSK)key, false));
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

    // these are bit-masks
    static final short WAIT_REJECTED_OVERLOAD = 1;
    static final short WAIT_TRANSFERRING_DATA = 2;
    static final short WAIT_FINISHED = 4;
    
    static final short WAIT_ALL = 
    	WAIT_REJECTED_OVERLOAD | WAIT_TRANSFERRING_DATA | WAIT_FINISHED;
    
    /**
     * Wait until either the transfer has started, we receive a 
     * RejectedOverload, or we get a terminal status code.
     * @param mask Bitmask indicating what NOT to wait for i.e. the situation when this function
     * exited last time (see WAIT_ constants above). Bits can also be set true even though they
     * were not valid, to indicate that the caller doesn't care about that bit.
     * If zero, function will throw an IllegalArgumentException.
     * @return Bitmask indicating present situation. Can be fed back to this function,
     * if nonzero.
     */
    public synchronized short waitUntilStatusChange(short mask) {
    	if(mask == WAIT_ALL) throw new IllegalArgumentException("Cannot ignore all!");
        while(true) {
        	short current = mask; // If any bits are set already, we ignore those states.
        	
       		if(hasForwardedRejectedOverload)
       			current |= WAIT_REJECTED_OVERLOAD;
        	
       		if(prb != null)
       			current |= WAIT_TRANSFERRING_DATA;
        	
        	if(status != NOT_FINISHED)
        		current |= WAIT_FINISHED;
        	
        	if(current != mask) return current;
        	
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
    	if(logMINOR) Logger.minor(this, "finish("+code+ ')');
        
        synchronized(this) {
            status = code;
            notifyAll();
            if(status == SUCCESS)
            	successFrom = next;
        }
        
        if(status == SUCCESS) {
        	if(next != null) {
        		next.onSuccess(false, key instanceof NodeSSK);
        	}
        	
        	if(key instanceof NodeCHK && next != null && 
        			(next.isOpennet() || node.passOpennetRefsThroughDarknet()) ) {
        		finishOpennet(next);
        	}
        }
        
		synchronized(this) {
			opennetFinished = true;
			notifyAll();
		}
        
    }

    /**
     * Do path folding, maybe.
     * Wait for either a CompletedAck or a ConnectDestination.
     * If the former, exit.
     * If we want a connection, reply with a ConnectReply, otherwise send a ConnectRejected and exit.
     * Add the peer.
     */
    private void finishOpennet(PeerNode next) {
    	
    	try {
    	MessageFilter mfAck = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(OPENNET_TIMEOUT).setType(DMT.FNPOpennetCompletedAck);
    	MessageFilter mfConnect = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(OPENNET_TIMEOUT).setType(DMT.FNPOpennetConnectDestination);
    	MessageFilter mf = mfAck.or(mfConnect).setMatchesDroppedConnection(true).setMatchesRestartedConnections(true);
    	
    	Message m;
		try {
			m = node.usm.waitFor(mf, this);
		} catch (DisconnectedException e) {
			return; // Ok
		}
    	
    	if(m == null) {
    		// Timeout
    		Logger.error(this, "Timed out waiting for opennet acknowledgement on "+this+" from "+next);
    		return;
    	} else if(m.getSpec() == DMT.FNPOpennetCompletedAck) {
    		if(logMINOR)
    			Logger.minor(this, "Destination does not want to path fold on "+this+" from "+next);
    		return;
    	} else if(!(m.getSpec() == DMT.FNPOpennetConnectDestination)) {
    		Logger.error(this, "Got a "+m+" expecting opennet completed / opennet connect destination on "+this+" from "+next);
    		return;
    	}
    	
    	// DMT.getSpec() === DMT.FNPOpennetConnectDestination
    	
    	byte[] noderef = ((ShortBuffer) m.getObject(DMT.OPENNET_NODEREF)).getData();
    	
    	SimpleFieldSet ref;
		try {
			ref = PeerNode.compressedNoderefToFieldSet(noderef, 0, noderef.length);
		} catch (FSParseException e) {
			Logger.error(this, "Could not parse opennet noderef for "+this+" from "+next, e);
			return;
		}
    	
		OpennetManager om = node.getOpennet();
    	try {
			if(om != null /* prevent race */ && !node.addNewOpennetNode(ref)) {
				// If we don't want it let somebody else have it
				synchronized(this) {
					opennetNoderef = noderef;
				}
				return;
			} else {
				Logger.error(this, "Added opennet noderef in "+this+" from "+next);
			}
		} catch (FSParseException e) {
			Logger.error(this, "Could not parse opennet noderef for "+this+" from "+next, e);
			return;
		} catch (PeerParseException e) {
			Logger.error(this, "Could not parse opennet noderef for "+this+" from "+next, e);
			return;
		} catch (ReferenceSignatureVerificationException e) {
			Logger.error(this, "Bad signature on opennet noderef for "+this+" from "+next+" : "+e, e);
			return;
		}
    	
    	// Send our reference
    	
    	Message msg = DMT.createFNPOpennetConnectReply(uid, new ShortBuffer( om.crypto.myCompressedFullRef()));
    	
    	try {
			next.sendAsync(msg, null, 0, this);
		} catch (NotConnectedException e) {
			// Hmmm... let the LRU deal with it
			if(logMINOR)
				Logger.minor(this, "Not connected sending ConnectReply on "+this+" to "+next);
		}
    	} finally {
    		synchronized(this) {
    			opennetFinished = true;
    			notifyAll();
    		}
    	}
	}

    // Opennet stuff
    
    /** Have we finished all opennet-related activities? */
    private boolean opennetFinished;
    
    /** Opennet noderef from next node */
    private byte[] opennetNoderef;
    
    public byte[] waitForOpennetNoderef() {
    	synchronized(this) {
    		while(true) {
    			if(opennetFinished) {
    				// Only one RequestHandler may take the noderef
    				byte[] ref = opennetNoderef;
    				opennetNoderef = null;
    				return ref;
    			}
    			try {
					wait(OPENNET_TIMEOUT);
				} catch (InterruptedException e) {
					// Ignore
				}
    		}
    	}
    }

    public PeerNode successFrom() {
    	return successFrom;
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

	private volatile Object totalBytesSync = new Object();
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
	
	synchronized boolean hasForwarded() {
		return hasForwarded;
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
	}
	
	private int recentlyFailedTimeLeft;

	synchronized int getRecentlyFailedTimeLeft() {
		return recentlyFailedTimeLeft;
	}
}
