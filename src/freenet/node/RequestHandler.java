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
public class RequestHandler implements Runnable {

    final Message req;
    final Node node;
    final long uid;
    private short htl;
    final PeerNode source;
    private double closestLoc;
    private boolean needsPubKey;
    final Key key;
    
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
        // FIXME should be more generic when implement SSKs
        key = (Key) req.getObject(DMT.FREENET_ROUTING_KEY);
        double keyLoc = key.toNormalizedDouble();
        if(PeerManager.distance(keyLoc, myLoc) < PeerManager.distance(keyLoc, closestLoc))
            closestLoc = myLoc;
        if(key instanceof NodeSSK)
        	needsPubKey = m.getBoolean(DMT.NEED_PUB_KEY);
    }

    public void run() {
        try {
        Logger.minor(this, "Handling a request: "+uid);
        htl = source.decrementHTL(htl);
        
        Message accepted = DMT.createFNPAccepted(uid);
        source.send(accepted);
        
        Object o = node.makeRequestSender(key, htl, uid, source, closestLoc, false, true, false);
        if(o instanceof KeyBlock) {
            KeyBlock block = (KeyBlock) o;
            Message df = createDataFound(block);
            source.send(df);
            if(key instanceof NodeSSK) {
                if(needsPubKey) {
                	DSAPublicKey key = ((NodeSSK)block.getKey()).getPubKey();
                	Message pk = DMT.createFNPSSKPubKey(uid, key.asBytes());
                	Logger.minor(this, "Sending PK: "+key+" "+key.writeAsField());
                	source.send(pk);
                }
            }
            if(block instanceof CHKBlock) {
            	PartiallyReceivedBlock prb =
            		new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE, block.getRawData());
            	BlockTransmitter bt =
            		new BlockTransmitter(node.usm, source, uid, prb, node.outputThrottle);
            	bt.send();
            }
            return;
        }
        RequestSender rs = (RequestSender) o;
        
        if(rs == null) { // ran out of htl?
            Message dnf = DMT.createFNPDataNotFound(uid);
            source.send(dnf);
            return;
        }
        
        boolean shouldHaveStartedTransfer = false;
        
        while(true) {
            
            if(rs.waitUntilStatusChange()) {
            	// Forward RejectedOverload
            	Message msg = DMT.createFNPRejectedOverload(uid, false);
            	source.sendAsync(msg, null, 0);
            }
            
            if(rs.transferStarted()) {
            	// Is a CHK.
                Message df = DMT.createFNPCHKDataFound(uid, rs.getHeaders());
                source.send(df);
                PartiallyReceivedBlock prb = rs.getPRB();
            	BlockTransmitter bt =
            	    new BlockTransmitter(node.usm, source, uid, prb, node.outputThrottle);
            	bt.send(); // either fails or succeeds; other side will see, we don't care
        	    return;
            }
            
            int status = rs.getStatus();
            
            switch(status) {
            	case RequestSender.NOT_FINISHED:
            	    continue;
            	case RequestSender.DATA_NOT_FOUND:
                    Message dnf = DMT.createFNPDataNotFound(uid);
            		source.sendAsync(dnf, null, 0);
            		return;
            	case RequestSender.GENERATED_REJECTED_OVERLOAD:
            	case RequestSender.TIMED_OUT:
            	case RequestSender.INTERNAL_ERROR:
            		// Locally generated.
            	    // Propagate back to source who needs to reduce send rate
            	    Message reject = DMT.createFNPRejectedOverload(uid, true);
            		source.sendAsync(reject, null, 0);
            		return;
            	case RequestSender.ROUTE_NOT_FOUND:
            	    // Tell source
            	    Message rnf = DMT.createFNPRouteNotFound(uid, rs.getHTL());
            		source.sendAsync(rnf, null, 0);
            		return;
            	case RequestSender.SUCCESS:
            		if(key instanceof NodeSSK) {
                        Message df = DMT.createFNPSSKDataFound(uid, rs.getHeaders(), rs.getSSKData());
                        source.send(df);
                        if(needsPubKey) {
                        	Message pk = DMT.createFNPSSKPubKey(uid, ((NodeSSK)rs.getSSKBlock().getKey()).getPubKey().asBytes());
                        	source.send(df);
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
            		source.sendAsync(reject, null, 0);
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
        }
    }

	private Message createDataFound(KeyBlock block) {
		if(block instanceof CHKBlock)
			return DMT.createFNPCHKDataFound(uid, block.getRawHeaders());
		else if(block instanceof SSKBlock)
			return DMT.createFNPSSKDataFound(uid, block.getRawHeaders(), block.getRawData());
		else
			throw new IllegalStateException("Unknown key block type: "+block.getClass());
	}

}
