package freenet.node;

import freenet.io.comm.Message;

/** A queued Message, and a callback, which may be null. */
public class MessageItem {
    
    Message msg;
    AsyncMessageCallback cb;
    
    public MessageItem(Message msg2, AsyncMessageCallback cb2) {
        this.msg = msg2;
        this.cb = cb2;
    }
}
