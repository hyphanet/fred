package freenet.support;

import freenet.node.AsyncMessageCallback;


public class LimitedRangeIntByteArrayMapElement {
    
    public LimitedRangeIntByteArrayMapElement(int packetNumber, byte[] data2, AsyncMessageCallback[] callbacks2) {
        this.packetNumber = packetNumber;
        this.data = data2;
        this.callbacks = callbacks2;
    }

    public final int packetNumber;
    public final byte[] data;
    public final AsyncMessageCallback[] callbacks;
}
