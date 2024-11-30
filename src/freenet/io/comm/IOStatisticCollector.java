/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import java.net.InetAddress;

import freenet.support.transport.ip.IPUtil;

public class IOStatisticCollector {
    private long totalBytesIn;
    private long totalBytesOut;

    public void reportReceivedBytes(InetAddress addr, int bytes) {
        if (isLocal(addr) || bytes <= 0) {
            return;
        }
        synchronized (this) {
            totalBytesIn += bytes;
        }
    }

    public void reportSentBytes(InetAddress addr, int bytes) {
        if (isLocal(addr) || bytes <= 0) {
            return;
        }
        synchronized (this) {
            totalBytesOut += bytes;
        }
    }

    public synchronized long[] getTotalIO() {
        return new long[]{totalBytesOut, totalBytesIn};
    }

    private static boolean isLocal(InetAddress address) {
        return address.isLinkLocalAddress() || address.isLoopbackAddress() || IPUtil.isSiteLocalAddress(address);
    }
}
