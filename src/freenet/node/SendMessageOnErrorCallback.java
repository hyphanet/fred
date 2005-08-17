package freenet.node;

import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.support.Logger;

/**
 * If the send fails, send the given message to the given node.
 * Otherwise do nothing.
 */
public class SendMessageOnErrorCallback implements AsyncMessageCallback {

    public String toString() {
        return super.toString() + ": "+msg+" "+dest;
    }
    
    Message msg;
    PeerNode dest;
    
    public SendMessageOnErrorCallback(Message message, PeerNode pn) {
        this.msg = message;
        this.dest = pn;
    }

    public void sent() {
        // Ignore
    }

    public void acknowledged() {
        // All done
    }

    public void disconnected() {
        try {
            dest.sendAsync(msg, null);
        } catch (NotConnectedException e) {
            Logger.minor(this, "Both source and destination disconnected: "+msg+" for "+this);
        }
    }

    public void fatalError() {
        disconnected();
    }
}
