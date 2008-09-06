/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
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
    private final short priority;
    
    public MessageItem(Message msg2, AsyncMessageCallback[] cb2, int alreadyReportedBytes, ByteCounter ctr) {
    	this.alreadyReportedBytes = alreadyReportedBytes;
        this.msg = msg2;
        this.cb = cb2;
        buf = null;
        formatted = false;
        this.ctrCallback = ctr;
        this.submitted = System.currentTimeMillis();
        priority = msg2.getSpec().getPriority();
    }

    public MessageItem(byte[] data, AsyncMessageCallback[] cb2, boolean formatted, int alreadyReportedBytes, ByteCounter ctr, short priority) {
    	this.alreadyReportedBytes = alreadyReportedBytes;
        this.cb = cb2;
        this.msg = null;
        this.buf = data;
        this.formatted = formatted;
        if(formatted && buf == null)
        	throw new NullPointerException();
        this.ctrCallback = ctr;
        this.submitted = System.currentTimeMillis();
        this.priority = priority;
    }

    /**
     * Return the data contents of this MessageItem.
     */
    public byte[] getData(PeerNode pn) {
        if(buf == null)
            buf = msg.encodeToPacket(pn);
        if(buf.length <= alreadyReportedBytes) {
        	Logger.error(this, "buf.length = "+buf.length+" but alreadyReportedBytes = "+alreadyReportedBytes+" on "+this);
        }
        return buf;
    }

    /**
     * @param length The actual number of bytes sent to send this message, including our share of the packet overheads,
     * *and including alreadyReportedBytes*, which is only used when deciding how many bytes to report to the throttle.
     */
	public void onSent(int length) {
        //NB: The fact that the bytes are counted before callback notifications is important for load management.
		if(ctrCallback != null) {
			try {
				ctrCallback.sentBytes(length);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t+" reporting "+length+" sent bytes on "+this, t);
			}
		}
		if(cb != null) {
			for(int i=0;i<cb.length;i++) {
				try {
					cb[i].sent();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" calling sent() on "+cb[i]+" for "+this, t);
				}
			}
		}
	}
	
	public short getPriority() {
		return priority;
	}
	
	@Override
	public String toString() {
		return super.toString()+":formatted="+formatted+",msg="+msg+",alreadyReported="+alreadyReportedBytes;
	}

	public void onDisconnect() {
		if(cb != null) {
			for(int i=0;i<cb.length;i++) {
				try {
					cb[i].disconnected();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" calling sent() on "+cb[i]+" for "+this, t);
				}
			}
		}
	}
}
