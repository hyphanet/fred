package freenet.node;

import java.io.IOException;

import freenet.crypt.DSAPublicKey;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.BlockReceiver;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.store.KeyCollisionException;
import freenet.support.Logger;
import freenet.support.ShortBuffer;

/**
 * Handles an incoming SSK insert.
 * SSKs need their own insert/request classes, see comments in SSKInsertSender.
 */
public class SSKInsertHandler implements Runnable {

    static final int PUBKEY_TIMEOUT = 10000;
    
    final Message req;
    final Node node;
    final long uid;
    final PeerNode source;
    final NodeSSK key;
    final long startTime;
    private SSKBlock block;
    private DSAPublicKey pubKey;
    private double closestLoc;
    private short htl;
    private SSKInsertSender sender;
    private byte[] data;
    private byte[] headers;
    private BlockReceiver br;
    private Thread runThread;
    private boolean sentSuccess;
    private boolean canCommit;
    private boolean collided;

    SSKInsertHandler(Message req, long id, Node node, long startTime) {
        this.req = req;
        this.node = node;
        this.uid = id;
        this.source = (PeerNode) req.getSource();
        this.startTime = startTime;
        key = (NodeSSK) req.getObject(DMT.FREENET_ROUTING_KEY);
        htl = req.getShort(DMT.HTL);
        closestLoc = req.getDouble(DMT.NEAREST_LOCATION);
        double targetLoc = key.toNormalizedDouble();
        double myLoc = node.lm.getLocation().getValue();
        if(PeerManager.distance(targetLoc, myLoc) < PeerManager.distance(targetLoc, closestLoc))
            closestLoc = myLoc;
        byte[] pubKeyHash = ((ShortBuffer)req.getObject(DMT.PUBKEY_HASH)).getData();
        pubKey = node.getKey(pubKeyHash);
        data = ((ShortBuffer) req.getObject(DMT.DATA)).getData();
        headers = ((ShortBuffer) req.getObject(DMT.BLOCK_HEADERS)).getData();
        canCommit = false;
    }
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public void run() {
        try {
        	realRun();
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
        } finally {
            Logger.minor(this, "Exiting InsertHandler.run() for "+uid);
            node.unlockUID(uid);
        }
    }

    private void realRun() {
        runThread = Thread.currentThread();
        
        // Send Accepted
        Message accepted = DMT.createFNPSSKAccepted(uid, pubKey == null);
        
        try {
			source.send(accepted);
		} catch (NotConnectedException e1) {
			Logger.minor(this, "Lost connection to source");
			return;
		}

		if(pubKey == null) {
			// Wait for pub key
			Logger.minor(this, "Waiting for pubkey on "+uid);
			
			MessageFilter mfPK = MessageFilter.create().setType(DMT.FNPSSKPubKey).setField(DMT.UID, uid).setSource(source).setTimeout(PUBKEY_TIMEOUT);
			
			try {
				Message pk = node.usm.waitFor(mfPK);
				if(pk == null) {
					Logger.normal(this, "Failed to receive FNPSSKPubKey for "+uid);
					return;
				}
				byte[] pubkeyAsBytes = ((ShortBuffer)pk.getObject(DMT.PUBKEY_AS_BYTES)).getData();
				try {
					pubKey = new DSAPublicKey(pubkeyAsBytes);
					Logger.minor(this, "Got pubkey on "+uid+" : "+pubKey);
					Message confirm = DMT.createFNPSSKPubKeyAccepted(uid);
					try {
						source.sendAsync(confirm, null, 0);
					} catch (NotConnectedException e) {
						Logger.minor(this, "Lost connection to source on "+uid);
						return;
					}
				} catch (IOException e) {
					Logger.error(this, "Invalid pubkey from "+source+" on "+uid);
					Message msg = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_SSK_ERROR);
					try {
						source.send(msg);
					} catch (NotConnectedException ee) {
						// Ignore
					}
					return;
				}
			} catch (DisconnectedException e) {
				Logger.minor(this, "Lost connection to source on "+uid);
				return;
			}
		}
		
		try {
			key.setPubKey(pubKey);
			block = new SSKBlock(data, headers, key, false);
		} catch (SSKVerifyException e1) {
			Logger.error(this, "Invalid SSK from "+source, e1);
			Message msg = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_SSK_ERROR);
			try {
				source.send(msg);
			} catch (NotConnectedException e) {
				// Ignore
			}
			return;
		}
		
		SSKBlock storedBlock = node.fetch(key);
		
		if(storedBlock != null && !storedBlock.equals(block)) {
			Message msg = DMT.createFNPSSKDataFound(uid, storedBlock.getRawHeaders(), storedBlock.getRawData());
			try {
				source.send(msg);
			} catch (NotConnectedException e) {
				Logger.minor(this, "Lost connection to source on "+uid);
			}
			block = storedBlock;
		}
		
		Logger.minor(this, "Got block for "+key+" for "+uid);
		
        if(htl == 0) {
        	Message msg = DMT.createFNPInsertReply(uid);
        	sentSuccess = true;
        	try {
				source.send(msg);
			} catch (NotConnectedException e) {
				// Ignore
			}
			canCommit = true;
            finish();
            return;
        }
        
        if(htl > 0)
            sender = node.makeInsertSender(block, htl, uid, source, false, closestLoc, true);
        
        boolean receivedRejectedOverload = false;
        
        while(true) {
            synchronized(sender) {
                try {
                	if(sender.getStatus() == SSKInsertSender.NOT_FINISHED)
                		sender.wait(5000);
                } catch (InterruptedException e) {
                	// Ignore
                }
            }

            if((!receivedRejectedOverload) && sender.receivedRejectedOverload()) {
            	receivedRejectedOverload = true;
            	// Forward it
            	Message m = DMT.createFNPRejectedOverload(uid, false);
            	try {
					source.send(m);
				} catch (NotConnectedException e) {
					Logger.minor(this, "Lost connection to source");
					return;
				}
            }
            
            if(sender.hasRecentlyCollided()) {
            	// Forward collision
            	data = sender.getData();
            	headers = sender.getHeaders();
        		try {
					block = new SSKBlock(data, headers, key, true);
				} catch (SSKVerifyException e1) {
					// Is verified elsewhere...
					throw new Error("Impossible: "+e1);
				}
            	Message msg = DMT.createFNPSSKDataFound(uid, headers, data);
            	try {
            		source.send(msg);
            	} catch (NotConnectedException e) {
            		Logger.minor(this, "Lost connection to source");
            		return;
            	}
            }
            
            int status = sender.getStatus();
            
            if(status == SSKInsertSender.NOT_FINISHED) {
                continue;
            }
            
            // Local RejectedOverload's (fatal).
            // Internal error counts as overload. It'd only create a timeout otherwise, which is the same thing anyway.
            // We *really* need a good way to deal with nodes that constantly R_O!
            if(status == SSKInsertSender.TIMED_OUT ||
            		status == SSKInsertSender.GENERATED_REJECTED_OVERLOAD ||
            		status == SSKInsertSender.INTERNAL_ERROR) {
                Message msg = DMT.createFNPRejectedOverload(uid, true);
                try {
					source.send(msg);
				} catch (NotConnectedException e) {
					Logger.minor(this, "Lost connection to source");
					return;
				}
                // Might as well store it anyway.
                if(status == SSKInsertSender.TIMED_OUT ||
                		status == SSKInsertSender.GENERATED_REJECTED_OVERLOAD)
                	canCommit = true;
                finish();
                return;
            }
            
            if(status == SSKInsertSender.ROUTE_NOT_FOUND || status == SSKInsertSender.ROUTE_REALLY_NOT_FOUND) {
                Message msg = DMT.createFNPRouteNotFound(uid, sender.getHTL());
                try {
					source.send(msg);
				} catch (NotConnectedException e) {
					Logger.minor(this, "Lost connection to source");
					return;
				}
                canCommit = true;
                finish();
                return;
            }
            
            if(status == SSKInsertSender.SUCCESS) {
            	Message msg = DMT.createFNPInsertReply(uid);
            	sentSuccess = true;
            	try {
					source.send(msg);
				} catch (NotConnectedException e) {
					Logger.minor(this, "Lost connection to source");
					return;
				}
                canCommit = true;
                finish();
                return;
            }
            
            // Otherwise...?
            Logger.error(this, "Unknown status code: "+sender.getStatusString());
            Message msg = DMT.createFNPRejectedOverload(uid, true);
            try {
				source.send(msg);
			} catch (NotConnectedException e) {
				// Ignore
			}
            finish();
            return;
        }
    }

    /**
     * If canCommit, and we have received all the data, and it
     * verifies, then commit it.
     */
    private void finish() {
    	Logger.minor(this, "Finishing");
    	
    	if(canCommit) {
    		try {
				node.store(block);
			} catch (KeyCollisionException e) {
				Logger.normal(this, "Collision on "+this);
			}
    	}
    }
    
}
