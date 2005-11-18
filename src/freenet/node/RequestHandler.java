package freenet.node;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.NodeCHK;
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
    final NodeCHK key;
    
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
        key = (NodeCHK) req.getObject(DMT.FREENET_ROUTING_KEY);
        double keyLoc = key.toNormalizedDouble();
        if(Math.abs(keyLoc - myLoc) < Math.abs(keyLoc - closestLoc))
            closestLoc = myLoc;
    }

    public void run() {
        try {
        htl = source.decrementHTL(htl);
        
        Message accepted = DMT.createFNPAccepted(uid);
        source.send(accepted);
        
        Object o = node.makeRequestSender(key, htl, uid, source, closestLoc, false, true);
        if(o instanceof CHKBlock) {
            CHKBlock block = (CHKBlock) o;
            Message df = DMT.createFNPDataFound(uid, block.getHeader());
            source.send(df);
            PartiallyReceivedBlock prb =
                new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE, block.getData());
            BlockTransmitter bt =
                new BlockTransmitter(node.usm, source, uid, prb);
            bt.send();
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
            
            rs.waitUntilStatusChange();
            
            if(rs.transferStarted()) {
                Message df = DMT.createFNPDataFound(uid, rs.getHeaders());
                source.send(df);
                PartiallyReceivedBlock prb = rs.getPRB();
            	BlockTransmitter bt =
            	    new BlockTransmitter(node.usm, source, uid, prb);
            	bt.send(); // either fails or succeeds; other side will see, we don't care
        	    return;
            }
            
            int status = rs.getStatus();
            
            switch(status) {
            	case RequestSender.NOT_FINISHED:
            	    continue;
            	case RequestSender.DATA_NOT_FOUND:
                    Message dnf = DMT.createFNPDataNotFound(uid);
            		source.sendAsync(dnf, null);
            		return;
            	case RequestSender.REJECTED_OVERLOAD:
            	    // Propagate back to source who needs to reduce send rate
            	    Message reject = DMT.createFNPRejectedOverload(uid);
            		source.sendAsync(reject, null);
            		return;
            	case RequestSender.ROUTE_NOT_FOUND:
            	    // Tell source
            	    Message rnf = DMT.createFNPRouteNotFound(uid, rs.getHTL());
            		source.sendAsync(rnf, null);
            		return;
            	case RequestSender.SUCCESS:
            	case RequestSender.TRANSFER_FAILED:
            	case RequestSender.VERIFY_FAILURE:
            	    if(shouldHaveStartedTransfer)
            	        throw new IllegalStateException("Got status code "+status+" but transfer not started");
            	    shouldHaveStartedTransfer = true;
            	    continue; // should have started transfer
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

}
