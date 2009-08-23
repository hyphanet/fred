/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import freenet.node.Node;
import freenet.support.BandwidthStatsContainer;
import freenet.support.UptimeContainer;

/**
 * Add/alter the containers contained in the database, so that
 * the upload/download statistics persist.
 * 
 * @author Artefact2
 */
public class PersistentStatsPutter implements DBJob {
	public static final int OFFSET = 60000;

	private Node n;
	private long latestNodeBytesOut = 0;
	private long latestNodeBytesIn = 0;
	private long latestUptimeVal = 0;
	private BandwidthStatsContainer latestBW = new BandwidthStatsContainer();
	private BandwidthStatsContainer latestBWStored = new BandwidthStatsContainer();
	private UptimeContainer latestUptime = new UptimeContainer();
	private UptimeContainer latestUptimeStored = new UptimeContainer();

	public PersistentStatsPutter(Node n) {
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

		ObjectSet<BandwidthStatsContainer> BSCresult = container.query(BandwidthStatsContainer.class);
		for(BandwidthStatsContainer bsc : BSCresult) {
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
		for(BandwidthStatsContainer bsc : BSCresult) {
			if(!bsc.equals(highestBSC)) {
				container.delete(bsc);
			}
		}
		this.latestBWStored = highestBSC;
		this.latestBW = this.latestBWStored;

		UptimeContainer highestUC = null;
		ObjectSet<UptimeContainer> UptimeResult = container.query(UptimeContainer.class);
		for(UptimeContainer uc : UptimeResult) {
			if(highestUC == null) {
				highestUC = uc;
				continue;
			}

			if(highestUC.creationTime < uc.creationTime) {
				highestUC = uc;
			}
		}
		if(highestUC == null) {
			highestUC = new UptimeContainer();
		}
		for(UptimeContainer uc : UptimeResult) {
			if(!uc.equals(highestUC)) {
				container.delete(uc);
			}
		}
		this.latestUptimeStored = highestUC;
		this.latestUptime = this.latestUptimeStored;

		container.commit();
	}

	public BandwidthStatsContainer getLatestBWData() {
		return this.latestBW;
	}

	public UptimeContainer getLatestUptimeData() {
		return this.latestUptime;
	}

	public void updateData() {
		// Update our values
		// 0 : total bytes out, 1 : total bytes in
		final long[] nodeBW = this.n.collector.getTotalIO();
		this.latestBW.totalBytesOut += nodeBW[0] - this.latestNodeBytesOut;
		this.latestBW.totalBytesIn += nodeBW[1] - this.latestNodeBytesIn;
		this.latestBW.creationTime = System.currentTimeMillis();
		this.latestNodeBytesOut = nodeBW[0];
		this.latestNodeBytesIn = nodeBW[1];
		
		final long uptime = this.n.getUptime();
		this.latestUptime.totalUptime += uptime - this.latestUptimeVal;
		this.latestUptime.creationTime = System.currentTimeMillis();
		this.latestUptimeVal = uptime;
	}

	public boolean run(ObjectContainer container, ClientContext context) {
		container.delete(this.latestBWStored);
		container.delete(this.latestUptimeStored);

		this.updateData();

		container.store(this.latestBW);
		container.store(this.latestUptime);
		container.commit();

		this.latestBWStored = this.latestBW;
		this.latestUptimeStored = this.latestUptime;

		return false;
	}
}
