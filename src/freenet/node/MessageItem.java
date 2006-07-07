package freenet.node;

import freenet.io.comm.Message;

/** A queued Message or byte[], and a callback, which may be null. */
public class MessageItem {
    
    final Message msg;
    byte[] buf;
    final AsyncMessageCallback[] cb;
    final long submitted;
    final int alreadyReportedBytes;
    /** If true, the buffer may contain several messages, and is formatted
     * for sending as a single packet.
     */
    final boolean formatted;
    
    public MessageItem(Message msg2, AsyncMessageCallback[] cb2, int alreadyReportedBytes) {
    	this.alreadyReportedBytes = alreadyReportedBytes;
        this.msg = msg2;
        this.cb = cb2;
        buf = null;
        formatted = false;
        this.submitted = System.currentTimeMillis();
    }

    public MessageItem(byte[] data, AsyncMessageCallback[] cb2, boolean formatted, int alreadyReportedBytes) {
    	this.alreadyReportedBytes = alreadyReportedBytes;
        this.cb = cb2;
        this.msg = null;
        this.buf = data;
        this.formatted = formatted;
        this.submitted = System.currentTimeMillis();
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
