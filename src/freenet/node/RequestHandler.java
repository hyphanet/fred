package freenet.node;

import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.NodeCHK;

/**
 * Handle an incoming request. Does not do the actual fetching; that
 * is separated off into RequestSender so we get transfer coalescing
 * and both ends for free. 
 */
public class RequestHandler implements Runnable {

    final Message req;
    final Node node;
    
    public RequestHandler(Message m, Node n) {
        req = m;
        node = n;
    }

    public void run() {
        long uid = req.getLong(DMT.UID);
        short htl = req.getShort(DMT.HTL);
        NodePeer source = (NodePeer) req.getSource();
        htl = source.decrementHTL(htl);
        // FIXME should be more generic when implement SSKs
        NodeCHK key = (NodeCHK) req.getObject(DMT.FREENET_ROUTING_KEY);
        
        Message accepted = DMT.createFNPAccepted(uid);
        source.send(accepted);
        
        Object o = node.makeRequestSender(key, htl, uid, source);
        if(o instanceof CHKBlock) {
            CHKBlock block = (CHKBlock) o;
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
            		source.sendAsync(dnf);
            		return;
            	case RequestSender.REJECTED_OVERLOAD:
            	    // Propagate back to source who needs to reduce send rate
            	    Message reject = DMT.createFNPRejectOverload(uid);
            		source.sendAsync(reject);
            		return;
            	case RequestSender.ROUTE_NOT_FOUND:
            	    // Tell source
            	    Message rnf = DMT.createFNPRouteNotFound(uid, rs.getHTL());
            		source.sendAsync(rnf);
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
    }

}
