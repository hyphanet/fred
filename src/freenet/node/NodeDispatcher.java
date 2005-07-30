package freenet.node;

import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;

import freenet.io.comm.DMT;
import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;
import freenet.support.Logger;

/**
 * @author amphibian
 * 
 * Dispatcher for unmatched FNP messages.
 * 
 * What can we get?
 * 
 * SwapRequests
 * 
 * DataRequests
 * 
 * InsertRequests
 * 
 * Probably a few others; those are the important bits.
 * 
 * Note that if in response to these we have to send a packet,
 * IT MUST BE DONE OFF-THREAD. Because sends will normally block.
 */
public class NodeDispatcher implements Dispatcher {

    final Node node;
    
    NodeDispatcher(Node node) {
        this.node = node;
    }
    
    public boolean handleMessage(Message m) {
        NodePeer source = (NodePeer)m.getSource();
        Logger.minor(this, "Dispatching "+m);
        if(m.getSpec() == DMT.FNPPing) {
            // Send an FNPPong
            Message reply = DMT.createFNPPong(m.getInt(DMT.PING_SEQNO));
            ((NodePeer)m.getSource()).sendAsync(reply);
            return true;
        }
        if(m.getSpec() == DMT.FNPLocChangeNotification) {
            double newLoc = m.getDouble(DMT.LOCATION);
            source.updateLocation(newLoc);
            return true;
        }
        if(m.getSpec() == DMT.FNPSwapRequest) {
            return node.lm.handleSwapRequest(m);
        }
        if(m.getSpec() == DMT.FNPSwapReply) {
            return node.lm.handleSwapReply(m);
        }
        if(m.getSpec() == DMT.FNPSwapRejected) {
            return node.lm.handleSwapRejected(m);
        }
        if(m.getSpec() == DMT.FNPSwapCommit) {
            return node.lm.handleSwapCommit(m);
        }
        if(m.getSpec() == DMT.FNPSwapComplete) {
            return node.lm.handleSwapComplete(m);
        }
        if(m.getSpec() == DMT.FNPRoutedPing) {
            return handleRouted(m);
        }
        if(m.getSpec() == DMT.FNPRoutedPong) {
            return handleRoutedReply(m);
        }
        if(m.getSpec() == DMT.FNPRoutedRejected) {
            return handleRoutedRejected(m);
        }
        if(m.getSpec() == DMT.FNPDataRequest) {
            return handleDataRequest(m);
        }
        if(m.getSpec() == DMT.FNPInsertRequest) {
            return handleInsertRequest(m);
        }
        return false;
    }

    /**
     * Handle an incoming FNPDataRequest.
     */
    private boolean handleDataRequest(Message m) {
        long id = m.getLong(DMT.UID);
        if(!node.lockUID(id)) return false;
        RequestHandler rh = new RequestHandler(m, id, node);
        Thread t = new Thread(rh);
        t.setDaemon(true);
        t.start();
        return true;
    }
    
    private boolean handleInsertRequest(Message m) {
        long id = m.getLong(DMT.UID);
        if(!node.lockUID(id)) {
            Logger.minor(this, "Could not lock ID "+id);
            return false;
        }
        InsertHandler rh = new InsertHandler(m, id, node);
        Thread t = new Thread(rh);
        t.setDaemon(true);
        t.start();
        Logger.minor(this, "Started InsertHandler for "+id);
        return true;
    }

    final Hashtable routedContexts = new Hashtable();
    
    class RoutedContext {
        long createdTime;
        long accessTime;
        NodePeer source;
        HashSet routedTo;
        Message msg;
        short lastHtl;
        
        RoutedContext(Message msg) {
            createdTime = accessTime = System.currentTimeMillis();
            source = (NodePeer)msg.getSource();
            routedTo = new HashSet();
            this.msg = msg;
            lastHtl = msg.getShort(DMT.HTL);
        }
        
        void addSent(NodePeer n) {
            routedTo.add(n);
        }
    }
    
    /**
     * Handle an FNPRoutedRejected message.
     */
    private boolean handleRoutedRejected(Message m) {
        long id = m.getLong(DMT.UID);
        Long lid = new Long(id);
        RoutedContext rc = (RoutedContext) routedContexts.get(lid);
        if(rc == null) {
            // Gah
            Logger.error(this, "Unrecognized FNPRoutedRejected");
            return false; // locally originated??
        }
        short htl = rc.lastHtl;
        if(rc.source != null)
            htl = rc.source.decrementHTL(htl);
        short ohtl = m.getShort(DMT.HTL);
        if(ohtl < htl) htl = ohtl;
        // Try routing to the next node
        forward(rc.msg, id, rc.source, htl, rc.msg.getDouble(DMT.TARGET_LOCATION), rc);
        return true;
    }

    /**
     * Handle a routed-to-a-specific-node message.
     * @param m
     * @return False if we want the message put back on the queue.
     */
    boolean handleRouted(Message m) {
        Logger.minor(this, "handleRouted("+m+")");
        if(m.getSource() != null && (!(m.getSource() instanceof NodePeer))) {
            Logger.error(this, "Routed message but source "+m.getSource()+" not a NodePeer!");
            return true;
        }
        long id = m.getLong(DMT.UID);
        Long lid = new Long(id);
        NodePeer pn = (NodePeer) (m.getSource());
        short htl = m.getShort(DMT.HTL);
        if(pn != null) htl = pn.decrementHTL(htl);
        RoutedContext ctx;
        ctx = (RoutedContext)routedContexts.get(lid);
        if(ctx != null) {
            ((NodePeer)m.getSource()).sendAsync(DMT.createFNPRoutedRejected(id, (short)(htl-1)));
            return true;
        }
        ctx = new RoutedContext(m);
        routedContexts.put(lid, ctx);
        // pn == null => originated locally, keep full htl
        double target = m.getDouble(DMT.TARGET_LOCATION);
        Logger.minor(this, "id "+id+" from "+pn+" htl "+htl+" target "+target);
        if(node.lm.getLocation().getValue() == target) {
            Logger.minor(this, "Dispatching "+m.getSpec()+" on "+node.portNumber);
            // Handle locally
            // Message type specific processing
            dispatchRoutedMessage(m, pn, id);
            return true;
        } else if(htl == 0) {
            Message reject = DMT.createFNPRoutedRejected(id, (short)0);
            if(pn != null)
                pn.sendAsync(reject);
            return true;
        } else {
            return forward(m, id, pn, htl, target, ctx);
        }
    }

    boolean handleRoutedReply(Message m) {
        long id = m.getLong(DMT.UID);
        Logger.minor(this, "Got reply: "+m);
        Long lid = new Long(id);
        RoutedContext ctx = (RoutedContext) routedContexts.get(lid);
        if(ctx == null) {
            Logger.error(this, "Unrecognized routed reply: "+m);
            return false;
        }
        NodePeer pn = ctx.source;
        if(pn == null) return false;
        pn.sendAsync(m);
        return true;
    }
    
    private boolean forward(Message m, long id, NodePeer pn, short htl, double target, RoutedContext ctx) {
        Logger.minor(this, "Should forward");
        // Forward
        m = preForward(m, htl);
        NodePeer next = node.peers.closerPeer(pn, ctx.routedTo, target, pn == null);
        Logger.minor(this, "Next: "+next+" message: "+m);
        if(next != null) {
            Logger.minor(this, "Forwarding "+m.getSpec()+" to "+next.getPeer().getPort());
            ctx.addSent(next);
            next.sendAsync(m);
        } else {
            Logger.minor(this, "Reached dead end for "+m.getSpec()+" on "+node.portNumber);
            // Reached a dead end...
            Message reject = DMT.createFNPRoutedRejected(id, htl);
            if(pn != null)
                pn.sendAsync(reject);
        }
        return true;
    }

    /**
     * Prepare a routed-to-node message for forwarding.
     */
    private Message preForward(Message m, short newHTL) {
        m.set(DMT.HTL, newHTL); // update htl
        if(m.getSpec() == DMT.FNPRoutedPing) {
            int x = m.getInt(DMT.COUNTER);
            x++;
            m.set(DMT.COUNTER, x);
        }
        return m;
    }

    /**
     * Deal with a routed-to-node message that landed on this node.
     * This is where message-type-specific code executes. 
     * @param m
     * @return
     */
    private boolean dispatchRoutedMessage(Message m, NodePeer src, long id) {
        if(m.getSpec() == DMT.FNPRoutedPing) {
            Logger.minor(this, "RoutedPing reached other side!");
            int x = m.getInt(DMT.COUNTER);
            Message reply = DMT.createFNPRoutedPong(id, x);
            src.sendAsync(reply);
            return true;
        }
        return false;
    }
}
