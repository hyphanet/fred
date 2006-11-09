/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.crypt.DSAPublicKey;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.support.Logger;

/**
 * Handle an incoming request. Does not do the actual fetching; that
 * is separated off into RequestSender so we get transfer coalescing
 * and both ends for free. 
 */
public class RequestHandler implements Runnable, ByteCounter {

	private static boolean logMINOR;
    final Message req;
    final Node node;
    final long uid;
    private short htl;
    final PeerNode source;
    private double closestLoc;
    private boolean needsPubKey;
    final Key key;
    private boolean finalTransferFailed = false;
    final boolean resetClosestLoc;
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public RequestHandler(Message m, long id, Node n) {
        req = m;
        node = n;
        uid = id;
        htl = req.getShort(DMT.HTL);
        source = (PeerNode) req.getSource();
        closestLoc = req.getDouble(DMT.NEAREST_LOCATION);
        double myLoc = n.lm.getLocation().getValue();
        key = (Key) req.getObject(DMT.FREENET_ROUTING_KEY);
        double keyLoc = key.toNormalizedDouble();
        if(PeerManager.distance(keyLoc, myLoc) < PeerManager.distance(keyLoc, closestLoc)) {
            closestLoc = myLoc;
            htl = Node.MAX_HTL;
            resetClosestLoc = true;
        } else
        	resetClosestLoc = false;
        if(key instanceof NodeSSK)
        	needsPubKey = m.getBoolean(DMT.NEED_PUB_KEY);
    }

    public void run() {
    	int status = RequestSender.NOT_FINISHED;
    	RequestSender rs = null;
        try {
        if(logMINOR) Logger.minor(this, "Handling a request: "+uid);
        htl = source.decrementHTL(htl);
        
        Message accepted = DMT.createFNPAccepted(uid);
        source.send(accepted, null);
        
        Object o = node.makeRequestSender(key, htl, uid, source, closestLoc, resetClosestLoc, false, true, false);
        if(o instanceof KeyBlock) {
            KeyBlock block = (KeyBlock) o;
            Message df = createDataFound(block);
            source.send(df, null);
            if(key instanceof NodeSSK) {
                if(needsPubKey) {
                	DSAPublicKey key = ((NodeSSK)block.getKey()).getPubKey();
                	Message pk = DMT.createFNPSSKPubKey(uid, key.asBytes());
                	if(logMINOR) Logger.minor(this, "Sending PK: "+key+" "+key.writeAsField());
                	source.send(pk, null);
                }
                status = RequestSender.SUCCESS; // for byte logging
            }
            if(block instanceof CHKBlock) {
            	PartiallyReceivedBlock prb =
            		new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE, block.getRawData());
            	BlockTransmitter bt =
            		new BlockTransmitter(node.usm, source, uid, prb, node.outputThrottle, this);
            	if(bt.send())
            		status = RequestSender.SUCCESS; // for byte logging
            }
            return;
        }
        rs = (RequestSender) o;
        
        if(rs == null) { // ran out of htl?
            Message dnf = DMT.createFNPDataNotFound(uid);
            source.send(dnf, null);
            status = RequestSender.DATA_NOT_FOUND; // for byte logging
            return;
        }
        
        boolean shouldHaveStartedTransfer = false;
        
        while(true) {
            
            if(rs.waitUntilStatusChange()) {
            	// Forward RejectedOverload
            	Message msg = DMT.createFNPRejectedOverload(uid, false);
            	source.sendAsync(msg, null, 0, null);
            }
            
            if(rs.transferStarted()) {
            	// Is a CHK.
                Message df = DMT.createFNPCHKDataFound(uid, rs.getHeaders());
                source.send(df, null);
                PartiallyReceivedBlock prb = rs.getPRB();
            	BlockTransmitter bt =
            	    new BlockTransmitter(node.usm, source, uid, prb, node.outputThrottle, this);
            	if(!bt.send()){
            		finalTransferFailed = true;
            	}
        	    return;
            }
            
            status = rs.getStatus();

            if(status == RequestSender.NOT_FINISHED) continue;
            
            switch(status) {
            	case RequestSender.NOT_FINISHED:
            	case RequestSender.DATA_NOT_FOUND:
                    Message dnf = DMT.createFNPDataNotFound(uid);
            		source.send(dnf, this);
            		return;
            	case RequestSender.GENERATED_REJECTED_OVERLOAD:
            	case RequestSender.TIMED_OUT:
            	case RequestSender.INTERNAL_ERROR:
            		// Locally generated.
            	    // Propagate back to source who needs to reduce send rate
            	    Message reject = DMT.createFNPRejectedOverload(uid, true);
            		source.send(reject, this);
            		return;
            	case RequestSender.ROUTE_NOT_FOUND:
            	    // Tell source
            	    Message rnf = DMT.createFNPRouteNotFound(uid, rs.getHTL());
            		source.send(rnf, this);
            		return;
            	case RequestSender.SUCCESS:
            		if(key instanceof NodeSSK) {
                        Message df = DMT.createFNPSSKDataFound(uid, rs.getHeaders(), rs.getSSKData());
                        source.send(df, this);
                        node.sentPayload(rs.getSSKData().length);
                        if(needsPubKey) {
                        	Message pk = DMT.createFNPSSKPubKey(uid, ((NodeSSK)rs.getSSKBlock().getKey()).getPubKey().asBytes());
                        	source.send(pk, this);
                        }
            		} else if(!rs.transferStarted()) {
            			Logger.error(this, "Status is SUCCESS but we never started a transfer on "+uid);
            		}
            		return;
            	case RequestSender.VERIFY_FAILURE:
            		if(key instanceof NodeCHK) {
            			if(shouldHaveStartedTransfer)
            				throw new IllegalStateException("Got status code "+status+" but transfer not started");
            			shouldHaveStartedTransfer = true;
            			continue; // should have started transfer
            		}
            	    reject = DMT.createFNPRejectedOverload(uid, true);
            		source.send(reject, this);
            		return;
            	case RequestSender.TRANSFER_FAILED:
            		if(key instanceof NodeCHK) {
            			if(shouldHaveStartedTransfer)
            				throw new IllegalStateException("Got status code "+status+" but transfer not started");
            			shouldHaveStartedTransfer = true;
            			continue; // should have started transfer
            		}
            		// Other side knows, right?
            		return;
            	default:
            	    throw new IllegalStateException("Unknown status code "+status);
            }
        }
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
        } finally {
            node.unlockUID(uid);
            if((!finalTransferFailed) && rs != null && status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD 
            		&& status != RequestSender.INTERNAL_ERROR) {
            	int sent = rs.getTotalSentBytes() + sentBytes;
            	int rcvd = rs.getTotalReceivedBytes() + receivedBytes;
            	if(key instanceof NodeSSK) {
            		if(logMINOR) Logger.minor(this, "Remote SSK fetch cost "+sent+"/"+rcvd+" bytes ("+status+")");
                	node.remoteSskFetchBytesSentAverage.report(sent);
                	node.remoteSskFetchBytesReceivedAverage.report(rcvd);
            	} else {
            		if(logMINOR) Logger.minor(this, "Remote CHK fetch cost "+sent+"/"+rcvd+" bytes ("+status+")");
                	node.remoteChkFetchBytesSentAverage.report(sent);
                	node.remoteChkFetchBytesReceivedAverage.report(rcvd);
            	}
            }

        }
    }

	private Message createDataFound(KeyBlock block) {
		if(block instanceof CHKBlock)
			return DMT.createFNPCHKDataFound(uid, block.getRawHeaders());
		else if(block instanceof SSKBlock) {
			node.sentPayload(block.getRawData().length);
			return DMT.createFNPSSKDataFound(uid, block.getRawHeaders(), block.getRawData());
		} else
			throw new IllegalStateException("Unknown key block type: "+block.getClass());
	}

	private int sentBytes;
	private int receivedBytes;
	private final Object bytesSync = new Object();
	
	public void sentBytes(int x) {
		synchronized(bytesSync) {
			sentBytes += x;
		}
	}

	public void receivedBytes(int x) {
		synchronized(bytesSync) {
			receivedBytes += x;
		}
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
	}

}
