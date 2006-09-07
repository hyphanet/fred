package freenet.support;

import freenet.node.AsyncMessageCallback;


public class LimitedRangeIntByteArrayMapElement {
    
    public LimitedRangeIntByteArrayMapElement(int packetNumber, byte[] data2, AsyncMessageCallback[] callbacks2) {
        this.packetNumber = packetNumber;
        this.data = data2;
        this.callbacks = callbacks2;
        createdTime = System.currentTimeMillis();
    }

    public final int packetNumber;
    public final byte[] data;
    public final AsyncMessageCallback[] callbacks;
    public final long createdTime;
    long reputTime = -1; /* Do NOT get rid of the initialisation otherwise calling LimitedRangeIntByteArrayMap.getReaddedTime() might return bullshit */
    
	public void reput() {
		this.reputTime = System.currentTimeMillis();
	}
}
