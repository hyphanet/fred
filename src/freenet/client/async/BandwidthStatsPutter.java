/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import freenet.node.Node;
import freenet.support.BandwidthStatsContainer;

/**
 * Add/alter the BandwidthStatsContainer contained in the database, so that
 * the upload/download statistics persist.
 * 
 * @author Artefact2
 */
public class BandwidthStatsPutter implements DBJob {
	public static final int OFFSET = 60000;

	private Node n;
	private long latestNodeBytesOut = 0;
	private long latestNodeBytesIn = 0;
	private BandwidthStatsContainer latest = new BandwidthStatsContainer();

	public BandwidthStatsPutter(Node n) {
		this.n = n;
	}

	/**
	 * Initiates that putter by fetching the latest container stored.
	 * This should be called only once.
	 *
	 * @param container Database to use.
	 */
	public void restorePreviousData(ObjectContainer container) {
		BandwidthStatsContainer highestBSC = null;

		ObjectSet<BandwidthStatsContainer> result = container.query(BandwidthStatsContainer.class);

		// Fetch the latest BSC
		for(BandwidthStatsContainer bsc : result) {
			if(highestBSC == null) {
				highestBSC = bsc;
				continue;
			}

			if(highestBSC.creationTime < bsc.creationTime) {
				highestBSC = bsc;
			}
		}

		if(highestBSC == null) {
			highestBSC = new BandwidthStatsContainer();
		}

		// Cleanup old stored items
		// BUT we keep our last BSC in case of a node crash before a new one
		// gets written.
		for(BandwidthStatsContainer bsc : result) {
			if(!bsc.equals(highestBSC)) {
				container.delete(bsc);
			}
		}

		this.latest = highestBSC;

		container.commit();
	}

	public BandwidthStatsContainer getLatestData() {
		return this.latest;
	}

	public boolean run(ObjectContainer container, ClientContext context) {
		container.delete(this.latest);

		// Update our BW values
		// 0 : total bytes out, 1 : total bytes in
		long[] nodeBW = this.n.collector.getTotalIO();
		this.latest.totalBytesOut += nodeBW[0] - this.latestNodeBytesOut;
		this.latest.totalBytesIn += nodeBW[1] - this.latestNodeBytesIn;
		this.latest.creationTime = System.currentTimeMillis();
		this.latestNodeBytesOut = nodeBW[0];
		this.latestNodeBytesIn = nodeBW[1];

		container.store(this.latest);
		container.commit();

		return false;
	}
}
