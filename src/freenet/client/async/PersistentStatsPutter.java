/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.Serializable;

import freenet.node.Node;
import freenet.support.BandwidthStatsContainer;
import freenet.support.UptimeContainer;

/**
 * Add/alter the containers contained in the database, so that
 * the upload/download statistics persist.
 * 
 * @author Artefact2
 */
public class PersistentStatsPutter implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int OFFSET = 60000;

	private long latestNodeBytesOut = 0;
	private long latestNodeBytesIn = 0;
	private long latestUptimeVal = 0;
	private BandwidthStatsContainer latestBW = new BandwidthStatsContainer();
	private UptimeContainer latestUptime = new UptimeContainer();

	public BandwidthStatsContainer getLatestBWData() {
		return this.latestBW;
	}

	public UptimeContainer getLatestUptimeData() {
		return this.latestUptime;
	}

	public void updateData(Node n) {
		// Update our values
		// 0 : total bytes out, 1 : total bytes in
		final long[] nodeBW = n.collector.getTotalIO();
		this.latestBW.totalBytesOut += nodeBW[0] - this.latestNodeBytesOut;
		this.latestBW.totalBytesIn += nodeBW[1] - this.latestNodeBytesIn;
		this.latestBW.creationTime = System.currentTimeMillis();
		this.latestNodeBytesOut = nodeBW[0];
		this.latestNodeBytesIn = nodeBW[1];
		
		final long uptime = n.getUptime();
		this.latestUptime.totalUptime += uptime - this.latestUptimeVal;
		this.latestUptime.creationTime = System.currentTimeMillis();
		this.latestUptimeVal = uptime;
	}

    public void addFrom(PersistentStatsPutter stored) {
        this.latestBW.addFrom(stored.latestBW);
        this.latestUptime.addFrom(stored.latestUptime);
    }

}
