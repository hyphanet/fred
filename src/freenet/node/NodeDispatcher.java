package freenet.node;

import java.util.HashSet;
import java.util.Hashtable;

import freenet.io.comm.DMT;
import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;
import freenet.io.comm.MessageType;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
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
        PeerNode source = (PeerNode)m.getSource();
        Logger.minor(this, "Dispatching "+m);
        MessageType spec = m.getSpec();
        if(spec == DMT.FNPPing) {
            // Send an FNPPong
            Message reply = DMT.createFNPPong(m.getInt(DMT.PING_SEQNO));
            try {
                ((PeerNode)m.getSource()).sendAsync(reply, null, 0, null); // nothing we can do if can't contact source
            } catch (NotConnectedException e) {
                Logger.minor(this, "Lost connection replying to "+m);
            }
            return true;
        }else if(spec == DMT.FNPLinkPing) {
        	long id = m.getLong(DMT.PING_SEQNO);
        	Message msg = DMT.createFNPLinkPong(id);
        	try {
				source.sendAsync(msg, null, 0, null);
			} catch (NotConnectedException e) {
				// Ignore
			}
        	return true;
        } else if(spec == DMT.FNPLinkPong) {
        	long id = m.getLong(DMT.PING_SEQNO);
        	source.receivedLinkPong(id);
        	return true;
        } else if(spec == DMT.FNPDetectedIPAddress) {
        	Peer p = (Peer) m.getObject(DMT.EXTERNAL_ADDRESS);
        	source.setRemoteDetectedPeer(p);
        	node.ipDetector.redetectAddress();
        } else if(spec == DMT.FNPVoid) {
        	return true;
        } else if(spec == DMT.nodeToNodeTextMessage) {
        	node.receivedNodeToNodeTextMessage(m);
        	return true;
        }
        
        if(!source.isRoutable()) return false;
        
        if(spec == DMT.FNPLocChangeNotification) {
            double newLoc = m.getDouble(DMT.LOCATION);
            source.updateLocation(newLoc);
            return true;
        } else if(spec == DMT.FNPSwapRequest) {
            return node.lm.handleSwapRequest(m);
        } else if(spec == DMT.FNPSwapReply) {
            return node.lm.handleSwapReply(m);
        } else if(spec == DMT.FNPSwapRejected) {
            return node.lm.handleSwapRejected(m);
        } else if(spec == DMT.FNPSwapCommit) {
            return node.lm.handleSwapCommit(m);
        } else if(spec == DMT.FNPSwapComplete) {
            return node.lm.handleSwapComplete(m);
        } else if(spec == DMT.FNPRoutedPing) {
            return handleRouted(m);
        } else if(spec == DMT.FNPRoutedPong) {
            return handleRoutedReply(m);
        } else if(spec == DMT.FNPRoutedRejected) {
            return handleRoutedRejected(m);
        } else if(spec == DMT.FNPCHKDataRequest) {
        	return handleDataRequest(m, false);
        } else if(spec == DMT.FNPSSKDataRequest) {
            return handleDataRequest(m, true);
        } else if(spec == DMT.FNPInsertRequest) {
        	return handleInsertRequest(m, false);
        } else if(spec == DMT.FNPSSKInsertRequest) {
            return handleInsertRequest(m, true);
        } 
        return false;
    }

    /**
     * Handle an incoming FNPDataRequest.
     */
    private boolean handleDataRequest(Message m, boolean isSSK) {
        long id = m.getLong(DMT.UID);
        if(node.recentlyCompleted(id)) {
            Message rejected = DMT.createFNPRejectedLoop(id);
            try {
                ((PeerNode)(m.getSource())).sendAsync(rejected, null, 0, null);
            } catch (NotConnectedException e) {
                Logger.normal(this, "Rejecting data request (loop, finished): "+e);
            }
            return true;
        }
        String rejectReason = node.shouldRejectRequest(!isSSK, false, isSSK);
        if(rejectReason != null) {
        	// can accept 1 CHK request every so often, but not with SSKs because they aren't throttled so won't sort out bwlimitDelayTime, which was the whole reason for accepting them when overloaded...
        	Logger.normal(this, "Rejecting request from "+m.getSource().getPeer()+" preemptively because "+rejectReason);
        	Message rejected = DMT.createFNPRejectedOverload(id, true);
        	try {
        		((PeerNode)(m.getSource())).sendAsync(rejected, null, 0, null);
            } catch (NotConnectedException e) {
                Logger.normal(this, "Rejecting (overload) data request from "+m.getSource().getPeer()+": "+e);
        	}
            node.completed(id);
            return true;
        }
        if(!node.lockUID(id)) {
            Logger.minor(this, "Could not lock ID "+id+" -> rejecting (already running)");
            Message rejected = DMT.createFNPRejectedLoop(id);
            try {
                ((PeerNode)(m.getSource())).sendAsync(rejected, null, 0, null);
            } catch (NotConnectedException e) {
                Logger.normal(this, "Rejecting insert request from "+m.getSource().getPeer()+": "+e);
            }
            return true;
        } else {
        	Logger.minor(this, "Locked "+id);
        }
        //if(!node.lockUID(id)) return false;
        RequestHandler rh = new RequestHandler(m, id, node);
        Thread t = new Thread(rh, "RequestHandler for UID "+id);
        t.setDaemon(true);
        t.start();
        return true;
    }
    
    private boolean handleInsertRequest(Message m, boolean isSSK) {
        long id = m.getLong(DMT.UID);
        if(node.recentlyCompleted(id)) {
            Message rejected = DMT.createFNPRejectedLoop(id);
            try {
                ((PeerNode)(m.getSource())).sendAsync(rejected, null, 0, null);
            } catch (NotConnectedException e) {
                Logger.normal(this, "Rejecting insert request from "+m.getSource().getPeer()+": "+e);
            }
            return true;
        }
        // SSKs don't fix bwlimitDelayTime so shouldn't be accepted when overloaded.
        String rejectReason = node.shouldRejectRequest(!isSSK, true, isSSK);
        if(rejectReason != null) {
        	Logger.normal(this, "Rejecting insert from "+m.getSource().getPeer()+" preemptively because "+rejectReason);
        	Message rejected = DMT.createFNPRejectedOverload(id, true);
        	try {
        		((PeerNode)(m.getSource())).sendAsync(rejected, null, 0, null);
            } catch (NotConnectedException e) {
                Logger.normal(this, "Rejecting (overload) insert request from "+m.getSource().getPeer()+": "+e);
        	}
            node.completed(id);
            return true;
        }
        if(!node.lockUID(id)) {
            Logger.minor(this, "Could not lock ID "+id+" -> rejecting (already running)");
            Message rejected = DMT.createFNPRejectedLoop(id);
            try {
                ((PeerNode)(m.getSource())).sendAsync(rejected, null, 0, null);
            } catch (NotConnectedException e) {
                Logger.normal(this, "Rejecting insert request from "+m.getSource().getPeer()+": "+e);
            }
            return true;
        }
        long now = System.currentTimeMillis();
        if(m.getSpec().equals(DMT.FNPSSKInsertRequest)) {
        	SSKInsertHandler rh = new SSKInsertHandler(m, id, node, now);
            Thread t = new Thread(rh, "InsertHandler for "+id+" on "+node.portNumber);
            t.setDaemon(true);
            t.start();
        } else {
        	InsertHandler rh = new InsertHandler(m, id, node, now);
        	Thread t = new Thread(rh, "InsertHandler for "+id+" on "+node.portNumber);
        	t.setDaemon(true);
        	t.start();
        }
        Logger.minor(this, "Started InsertHandler for "+id);
        return true;
    }

    final Hashtable routedContexts = new Hashtable();
    
    static class RoutedContext {
        long createdTime;
        long accessTime;
        PeerNode source;
        final HashSet routedTo;
        final HashSet notIgnored;
        Message msg;
        short lastHtl;
        
        RoutedContext(Message msg) {
            createdTime = accessTime = System.currentTimeMillis();
            source = (PeerNode)msg.getSource();
            routedTo = new HashSet();
            notIgnored = new HashSet();
            this.msg = msg;
            lastHtl = msg.getShort(DMT.HTL);
        }
        
        void addSent(PeerNode n) {
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
        if((m.getSource() != null) && (!(m.getSource() instanceof PeerNode))) {
            Logger.error(this, "Routed message but source "+m.getSource()+" not a PeerNode!");
            return true;
        }
        long id = m.getLong(DMT.UID);
        Long lid = new Long(id);
        PeerNode pn = (PeerNode) (m.getSource());
        short htl = m.getShort(DMT.HTL);
        if(pn != null) htl = pn.decrementHTL(htl);
        RoutedContext ctx;
        ctx = (RoutedContext)routedContexts.get(lid);
        if(ctx != null) {
            try {
                ((PeerNode)m.getSource()).sendAsync(DMT.createFNPRoutedRejected(id, (short)(htl-1)), null, 0, null);
            } catch (NotConnectedException e) {
                Logger.minor(this, "Lost connection rejecting "+m);
            }
            return true;
        }
        ctx = new RoutedContext(m);
        routedContexts.put(lid, ctx);
        // pn == null => originated locally, keep full htl
        double target = m.getDouble(DMT.TARGET_LOCATION);
        Logger.minor(this, "id "+id+" from "+pn+" htl "+htl+" target "+target);
        if(Math.abs(node.lm.getLocation().getValue() - target) <= Double.MIN_VALUE) {
            Logger.minor(this, "Dispatching "+m.getSpec()+" on "+node.portNumber);
            // Handle locally
            // Message type specific processing
            dispatchRoutedMessage(m, pn, id);
            return true;
        } else if(htl == 0) {
            Message reject = DMT.createFNPRoutedRejected(id, (short)0);
            if(pn != null) try {
                pn.sendAsync(reject, null, 0, null);
            } catch (NotConnectedException e) {
                Logger.minor(this, "Lost connection rejecting "+m);
            }
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
        PeerNode pn = ctx.source;
        if(pn == null) return false;
        try {
            pn.sendAsync(m, null, 0, null);
        } catch (NotConnectedException e) {
            Logger.minor(this, "Lost connection forwarding "+m+" to "+pn);
        }
        return true;
    }
    
    private boolean forward(Message m, long id, PeerNode pn, short htl, double target, RoutedContext ctx) {
        Logger.minor(this, "Should forward");
        // Forward
        m = preForward(m, htl);
        while(true) {
            PeerNode next = node.peers.closerPeer(pn, ctx.routedTo, ctx.notIgnored, target, true);
            Logger.minor(this, "Next: "+next+" message: "+m);
            if(next != null) {
            	// next is connected, or at least has been => next.getPeer() CANNOT be null.
                Logger.minor(this, "Forwarding "+m.getSpec()+" to "+next.getPeer().getPort());
                ctx.addSent(next);
                try {
                    next.sendAsync(m, null, 0, null);
                } catch (NotConnectedException e) {
                    continue;
                }
            } else {
                Logger.minor(this, "Reached dead end for "+m.getSpec()+" on "+node.portNumber);
                // Reached a dead end...
                Message reject = DMT.createFNPRoutedRejected(id, htl);
                if(pn != null) try {
                    pn.sendAsync(reject, null, 0, null);
                } catch (NotConnectedException e) {
                    Logger.error(this, "Cannot send reject message back to source "+pn);
                    return true;
                }
            }
            return true;
        }
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
    private boolean dispatchRoutedMessage(Message m, PeerNode src, long id) {
        if(m.getSpec() == DMT.FNPRoutedPing) {
            Logger.minor(this, "RoutedPing reached other side!");
            int x = m.getInt(DMT.COUNTER);
            Message reply = DMT.createFNPRoutedPong(id, x);
            try {
                src.sendAsync(reply, null, 0, null);
            } catch (NotConnectedException e) {
                Logger.minor(this, "Lost connection replying to "+m+" in dispatchRoutedMessage");
            }
            return true;
        }
        return false;
    }
}
