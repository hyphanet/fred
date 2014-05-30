/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

import freenet.io.comm.AsyncMessageCallback;

public class LimitedRangeIntByteArrayMapElement {
    public final int packetNumber;
    public final byte[] data;
    public final AsyncMessageCallback[] callbacks;
    public final long createdTime;
    public final short priority;
    long reputTime;

    public LimitedRangeIntByteArrayMapElement(int packetNumber, byte[] data2, AsyncMessageCallback[] callbacks2,
            short priority) {
        this.packetNumber = packetNumber;
        this.data = data2;
        this.callbacks = callbacks2;
        this.priority = priority;
        createdTime = System.currentTimeMillis();
    }

    public void reput() {
        this.reputTime = System.currentTimeMillis();
    }
}
