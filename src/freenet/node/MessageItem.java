/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.support.Logger;

/** A queued byte[], maybe including a Message, and a callback, which may be null.
 * Note that we always create the byte[] on construction, as almost everywhere
 * which uses a MessageItem needs to know its length immediately. */
public class MessageItem {

	final Message msg;
	final byte[] buf;
	final AsyncMessageCallback[] cb;
	final long submitted;
	/** If true, the buffer may contain several messages, and is formatted
	 * for sending as a single packet.
	 */
	final boolean formatted;
	final ByteCounter ctrCallback;
	private final short priority;
	private long cachedID;
	private boolean hasCachedID;
	final boolean sendLoadRT;
	final boolean sendLoadBulk;
	private long deadline;

	public MessageItem(Message msg2, AsyncMessageCallback[] cb2, ByteCounter ctr, short overridePriority) {
		this.msg = msg2;
		this.cb = cb2;
		formatted = false;
		this.ctrCallback = ctr;
		this.submitted = System.currentTimeMillis();
		if(overridePriority > 0)
			priority = overridePriority;
		else
			priority = msg2.getPriority();
		this.sendLoadRT = msg2.needsLoadRT();
		this.sendLoadBulk = msg2.needsLoadBulk();
		buf = msg.encodeToPacket();
		if(buf.length > NewPacketFormat.MAX_MESSAGE_SIZE) {
			// This is bad because fairness between UID's happens at the level of message queueing,
			// and the window size is frequently very small, so if we have really big messages they
			// could cause big problems e.g. starvation of other messages, resulting in timeouts 
			// (especially if there are retransmits).
			Logger.error(this, "WARNING: Message too big: "+buf.length+" for "+msg2, new Exception("error"));
		}
	}

	public MessageItem(Message msg2, AsyncMessageCallback[] cb2, ByteCounter ctr) {
		this(msg2, cb2, ctr, (short)-1);
	}

	public MessageItem(byte[] data, AsyncMessageCallback[] cb2, boolean formatted, ByteCounter ctr, short priority, boolean sendLoadRT, boolean sendLoadBulk) {
		this.cb = cb2;
		this.msg = null;
		this.buf = data;
		this.formatted = formatted;
		if(formatted && buf == null)
			throw new NullPointerException();
		this.ctrCallback = ctr;
		this.submitted = System.currentTimeMillis();
		this.priority = priority;
		this.sendLoadRT = sendLoadRT;
		this.sendLoadBulk = sendLoadBulk;
	}

	/**
	 * Return the data contents of this MessageItem.
	 */
	public byte[] getData() {
		return buf;
	}

	public int getLength() {
		return buf.length;
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
	}

	public short getPriority() {
		return priority;
	}

	@Override
	public String toString() {
		return super.toString()+":formatted="+formatted+",msg="+msg;
	}

	public void onDisconnect() {
		if(cb != null) {
			for(AsyncMessageCallback cbi: cb) {
				try {
					cbi.disconnected();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" calling sent() on "+cbi+" for "+this, t);
				}
			}
		}
	}

	public void onFailed() {
		if(cb != null) {
			for(AsyncMessageCallback cbi: cb) {
				try {
					cbi.fatalError();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" calling sent() on "+cbi+" for "+this, t);
				}
			}
		}
	}

	public synchronized long getID() {
		if(hasCachedID) return cachedID;
		cachedID = generateID();
		hasCachedID = true;
		return cachedID;
	}
	
	private long generateID() {
		if(msg == null) return -1;
		Object o = msg.getObject(DMT.UID);
		if(o == null || !(o instanceof Long)) {
			return -1;
		} else {
			return (Long)o;
		}
	}

	/** Called the first time we have sent all of the message. */
	public void onSentAll() {
		if(cb != null) {
			for(AsyncMessageCallback cbi: cb) {
				try {
					cbi.sent();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" calling sent() on "+cbi+" for "+this, t);
				}
			}
		}
	}

	/** Set the deadline for this message. Called when a message is unqueued, when
	 * we start to send it. Used if the message does not entirely fit in the 
	 * packet, and also if it is retransmitted.
	 * @param time The time (in the future) to set the deadline to.
	 */
	public synchronized void setDeadline(long time) {
		deadline = time;
	}
	
	/** Clear the deadline for this message. */
	public synchronized void clearDeadline() {
		deadline = 0;
	}
	
	/** Get the deadline for this message. 0 means no deadline has been set. */
	public synchronized long getDeadline() {
		return deadline;
	}
}
