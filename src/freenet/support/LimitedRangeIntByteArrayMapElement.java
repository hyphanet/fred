package freenet.support;

import freenet.io.comm.AsyncMessageCallback;


public class LimitedRangeIntByteArrayMapElement {

	public LimitedRangeIntByteArrayMapElement(int packetNumber, byte[] data2, AsyncMessageCallback[] callbacks2, short priority) {
		this.packetNumber = packetNumber;
		this.data = data2;
		this.callbacks = callbacks2;
		this.priority = priority;
		createdTime = System.currentTimeMillis();
	}

	public final int packetNumber;
	public final byte[] data;
	public final AsyncMessageCallback[] callbacks;
	public final long createdTime;
	public final short priority;
	long reputTime;

	public void reput() {
		this.reputTime = System.currentTimeMillis();
	}
}
