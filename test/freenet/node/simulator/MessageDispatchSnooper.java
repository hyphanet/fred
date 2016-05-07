package freenet.node.simulator;

import freenet.io.comm.Dispatcher;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.node.Node;

/** Intercepts messages between nodes in a simulation. Only sees new messages that are dispatched, 
 * e.g. new requests. This is cheap, and sufficient for e.g. identifying which nodes a request
 * goes to. */
public abstract class MessageDispatchSnooper {

    private class MessageInterceptor implements Dispatcher {
        
        final Node recipient;
        final Dispatcher realDispatcher;
        
        MessageInterceptor(Node recipient, Dispatcher realDispatcher) {
            this.recipient = recipient;
            this.realDispatcher = realDispatcher;
        }
        
        @Override
        public boolean handleMessage(Message m) {
            snoopMessage(recipient, m);
            return realDispatcher.handleMessage(m);
        }
        
    }
    
    public void add(Node target) {
        MessageCore core = target.getUSM();
        Dispatcher d = core.getDispatcher();
        MessageInterceptor mi = new MessageInterceptor(target, d);
        core.setDispatcher(mi);
    }
    
    protected abstract void snoopMessage(Node recipient, Message m);
    
}
