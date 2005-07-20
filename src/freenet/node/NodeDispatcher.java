package freenet.node;

import freenet.io.comm.DMT;
import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;

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

    public boolean handleMessage(Message m) {
        if(m.getSpec() == DMT.FNPPing) {
            // Send an FNPPong
            Message reply = DMT.createFNPPong(m.getInt(DMT.PING_SEQNO));
            ((NodePeer)m.getSource()).sendAsync(reply);
            return true;
        }
        // TODO Auto-generated method stub
        return false;
    }

}
