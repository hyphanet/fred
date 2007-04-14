/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.Message;
import freenet.support.Logger;

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
    final ByteCounter ctrCallback;
    
    public MessageItem(Message msg2, AsyncMessageCallback[] cb2, int alreadyReportedBytes, ByteCounter ctr) {
    	this.alreadyReportedBytes = alreadyReportedBytes;
        this.msg = msg2;
        this.cb = cb2;
        buf = null;
        formatted = false;
        this.ctrCallback = ctr;
        this.submitted = System.currentTimeMillis();
    }

    public MessageItem(byte[] data, AsyncMessageCallback[] cb2, boolean formatted, int alreadyReportedBytes, ByteCounter ctr) {
    	this.alreadyReportedBytes = alreadyReportedBytes;
        this.cb = cb2;
        this.msg = null;
        this.buf = data;
        this.formatted = formatted;
        if(formatted && buf == null)
        	throw new NullPointerException();
        this.ctrCallback = ctr;
        this.submitted = System.currentTimeMillis();
    }

    /**
     * Return the data contents of this MessageItem.
     */
    public byte[] getData(PeerNode pn) {
        if(buf == null)
            buf = msg.encodeToPacket(pn);
        return buf;
    }

	public void onSent(int length) {
		if(ctrCallback != null) {
			try {
				ctrCallback.sentBytes(length);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t+" reporting "+length+" sent bytes on "+this);
			}
		}
		if(cb != null) {
			for(int i=0;i<cb.length;i++) {
				try {
					cb[i].sent();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" calling sent() on "+cb[i]+" for "+this);
				}
			}
		}
	}
}
