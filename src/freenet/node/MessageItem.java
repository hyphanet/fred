package freenet.node;

import freenet.io.comm.Message;

/** A queued Message or byte[], and a callback, which may be null. */
public class MessageItem {
    
    final Message msg;
    byte[] buf;
    final AsyncMessageCallback[] cb;
    
    public MessageItem(Message msg2, AsyncMessageCallback[] cb2) {
        this.msg = msg2;
        this.cb = cb2;
        buf = null;
    }

    public MessageItem(byte[] data, AsyncMessageCallback[] cb2) {
        this.cb = cb2;
        this.msg = null;
        this.buf = data;
    }
    
    /**
     * Return the data contents of this MessageItem.
     */
    public byte[] getData(FNPPacketMangler mangler, PeerNode pn) {
        if(buf == null)
            buf = msg.encodeToPacket(mangler, pn);
        return buf;
    }
}
