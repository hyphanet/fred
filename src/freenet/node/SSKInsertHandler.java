/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.crypt.CryptFormatException;
import freenet.crypt.DSAPublicKey;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.store.KeyCollisionException;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.ShortBuffer;

/**
 * Handles an incoming SSK insert.
 * SSKs need their own insert/request classes, see comments in SSKInsertSender.
 */
public class SSKInsertHandler implements Runnable, ByteCounter {

	private static boolean logMINOR;
	
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
    private final boolean resetClosestLoc;
    private short htl;
    private SSKInsertSender sender;
    private byte[] data;
    private byte[] headers;
    private boolean canCommit;
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
        if(PeerManager.distance(targetLoc, myLoc) < PeerManager.distance(targetLoc, closestLoc)) {
            closestLoc = myLoc;
            htl = node.maxHTL();
            resetClosestLoc = true;
        } else resetClosestLoc = false;
        byte[] pubKeyHash = ((ShortBuffer)req.getObject(DMT.PUBKEY_HASH)).getData();
        pubKey = node.getKey(pubKeyHash);
        data = ((ShortBuffer) req.getObject(DMT.DATA)).getData();
        headers = ((ShortBuffer) req.getObject(DMT.BLOCK_HEADERS)).getData();
        canCommit = false;
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
    }
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public void run() {
        try {
        	realRun();
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
        } finally {
            if(logMINOR) Logger.minor(this, "Exiting InsertHandler.run() for "+uid);
            node.unlockUID(uid, true, true);
        }
    }

    private void realRun() {
        // Send Accepted
        Message accepted = DMT.createFNPSSKAccepted(uid, pubKey == null);
        
        try {
			source.sendSync(accepted, this);
		} catch (NotConnectedException e1) {
			if(logMINOR) Logger.minor(this, "Lost connection to source");
			return;
		}

		if(pubKey == null) {
			// Wait for pub key
			if(logMINOR) Logger.minor(this, "Waiting for pubkey on "+uid);
			
			MessageFilter mfPK = MessageFilter.create().setType(DMT.FNPSSKPubKey).setField(DMT.UID, uid).setSource(source).setTimeout(PUBKEY_TIMEOUT);
			
			try {
				Message pk = node.usm.waitFor(mfPK, this);
				if(pk == null) {
					Logger.normal(this, "Failed to receive FNPSSKPubKey for "+uid);
					return;
				}
				byte[] pubkeyAsBytes = ((ShortBuffer)pk.getObject(DMT.PUBKEY_AS_BYTES)).getData();
				try {
					pubKey = DSAPublicKey.create(pubkeyAsBytes);
					if(logMINOR) Logger.minor(this, "Got pubkey on "+uid+" : "+pubKey);
					Message confirm = DMT.createFNPSSKPubKeyAccepted(uid);
					try {
						source.sendAsync(confirm, null, 0, this);
					} catch (NotConnectedException e) {
						if(logMINOR) Logger.minor(this, "Lost connection to source on "+uid);
						return;
					}
				} catch (CryptFormatException e) {
					Logger.error(this, "Invalid pubkey from "+source+" on "+uid);
					Message msg = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_SSK_ERROR);
					try {
						source.sendSync(msg, this);
					} catch (NotConnectedException ee) {
						// Ignore
					}
					return;
				}
			} catch (DisconnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection to source on "+uid);
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
				source.sendSync(msg, this);
			} catch (NotConnectedException e) {
				// Ignore
			}
			return;
		}
		
		SSKBlock storedBlock = node.fetch(key, false);
		
		if((storedBlock != null) && !storedBlock.equals(block)) {
			Message msg = DMT.createFNPSSKDataFound(uid, storedBlock.getRawHeaders(), storedBlock.getRawData());
			try {
				source.sendSync(msg, this);
				node.sentPayload(storedBlock.getRawData().length);
			} catch (NotConnectedException e) {
				if(logMINOR) Logger.minor(this, "Lost connection to source on "+uid);
			}
			block = storedBlock;
		}
		
		if(logMINOR) Logger.minor(this, "Got block for "+key+" for "+uid);
		
        if(htl == 0) {
        	Message msg = DMT.createFNPInsertReply(uid);
        	try {
				source.sendSync(msg, this);
			} catch (NotConnectedException e) {
				// Ignore
			}
			canCommit = true;
            finish(SSKInsertSender.SUCCESS);
            return;
        }
        
        if(htl > 0)
            sender = node.makeInsertSender(block, htl, uid, source, false, closestLoc, resetClosestLoc, true);
        
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
					source.sendSync(m, this);
				} catch (NotConnectedException e) {
					if(logMINOR) Logger.minor(this, "Lost connection to source");
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
            		source.sendSync(msg, this);
    				node.sentPayload(data.length);
            	} catch (NotConnectedException e) {
            		if(logMINOR) Logger.minor(this, "Lost connection to source");
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
            if((status == SSKInsertSender.TIMED_OUT) ||
            		(status == SSKInsertSender.GENERATED_REJECTED_OVERLOAD) ||
            		(status == SSKInsertSender.INTERNAL_ERROR)) {
                Message msg = DMT.createFNPRejectedOverload(uid, true);
                try {
					source.sendSync(msg, this);
				} catch (NotConnectedException e) {
					if(logMINOR) Logger.minor(this, "Lost connection to source");
					return;
				}
                // Might as well store it anyway.
                if((status == SSKInsertSender.TIMED_OUT) ||
                		(status == SSKInsertSender.GENERATED_REJECTED_OVERLOAD))
                	canCommit = true;
                finish(status);
                return;
            }
            
            if((status == SSKInsertSender.ROUTE_NOT_FOUND) || (status == SSKInsertSender.ROUTE_REALLY_NOT_FOUND)) {
                Message msg = DMT.createFNPRouteNotFound(uid, sender.getHTL());
                try {
					source.sendSync(msg, null);
				} catch (NotConnectedException e) {
					if(logMINOR) Logger.minor(this, "Lost connection to source");
					return;
				}
                canCommit = true;
                finish(status);
                return;
            }
            
            if(status == SSKInsertSender.SUCCESS) {
            	Message msg = DMT.createFNPInsertReply(uid);
            	try {
					source.sendSync(msg, null);
				} catch (NotConnectedException e) {
					if(logMINOR) Logger.minor(this, "Lost connection to source");
					return;
				}
                canCommit = true;
                finish(status);
                return;
            }
            
            // Otherwise...?
            Logger.error(this, "Unknown status code: "+sender.getStatusString());
            Message msg = DMT.createFNPRejectedOverload(uid, true);
            try {
				source.sendSync(msg, null);
			} catch (NotConnectedException e) {
				// Ignore
			}
            finish(status);
            return;
        }
    }

    /**
     * If canCommit, and we have received all the data, and it
     * verifies, then commit it.
     */
    private void finish(int code) {
    	if(logMINOR) Logger.minor(this, "Finishing");
    	
    	if(canCommit) {
    		try {
				node.store(block, block.getKey().toNormalizedDouble());
			} catch (KeyCollisionException e) {
				Logger.normal(this, "Collision on "+this);
			}
    	}
    	
        if(code != SSKInsertSender.TIMED_OUT && code != SSKInsertSender.GENERATED_REJECTED_OVERLOAD &&
        		code != SSKInsertSender.INTERNAL_ERROR && code != SSKInsertSender.ROUTE_REALLY_NOT_FOUND) {
        	int totalSent = getTotalSentBytes();
        	int totalReceived = getTotalReceivedBytes();
        	if(sender != null) {
        		totalSent += sender.getTotalSentBytes();
        		totalReceived += sender.getTotalReceivedBytes();
        	}
        	if(logMINOR) Logger.minor(this, "Remote SSK insert cost "+totalSent+ '/' +totalReceived+" bytes ("+code+ ')');
        	node.nodeStats.remoteSskInsertBytesSentAverage.report(totalSent);
        	node.nodeStats.remoteSskInsertBytesReceivedAverage.report(totalReceived);
        	if(code == SSKInsertSender.SUCCESS) {
        		// Can report both sides
        		node.nodeStats.successfulSskInsertBytesSentAverage.report(totalSent);
        		node.nodeStats.successfulSskInsertBytesReceivedAverage.report(totalReceived);
        	}
        }

    }

    private final Object totalBytesSync = new Object();
    private int totalBytesSent;
    private int totalBytesReceived;
    
	public void sentBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesSent += x;
		}
	}

	public void receivedBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesReceived += x;
		}
	}
	
	public int getTotalSentBytes() {
		return totalBytesSent;
	}
	
	public int getTotalReceivedBytes() {
		return totalBytesReceived;
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
	}
    
}
