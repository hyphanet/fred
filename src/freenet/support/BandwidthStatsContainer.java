/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

/**
 * Contains bandwidth statistics.
 * 
 * @author Artefact2
 */
public class BandwidthStatsContainer {
	public long creationTime = 0;
	public long totalBytesOut = 0;
	public long totalBytesIn = 0;

	@Override
	public boolean equals(Object o) {
		if(o == null) return false;
	if(o.getClass() == BandwidthStatsContainer.class) {
		BandwidthStatsContainer oB = (BandwidthStatsContainer) o;
		return (oB.creationTime == this.creationTime) &&
			(oB.totalBytesIn == this.totalBytesIn) &&
			(oB.totalBytesOut == this.totalBytesOut);
		} else return false;
	}

	@Override
	public int hashCode() {
		int hash = 3;
		hash = 41 * hash + (int) (this.creationTime ^ (this.creationTime >>> 32));
		hash = 41 * hash + (int) (this.totalBytesOut ^ (this.totalBytesOut >>> 32));
		hash = 41 * hash + (int) (this.totalBytesIn ^ (this.totalBytesIn >>> 32));
		return hash;
	}
}
