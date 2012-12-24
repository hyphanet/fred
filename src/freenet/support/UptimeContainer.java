/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

/**
 * Contains uptime statistics.
 *
 * @author Artefact2
 */
public class UptimeContainer {
	public long creationTime = 0;
	public long totalUptime = 0;

	@Override
	public boolean equals(Object o) {
		if(o == null) return false;
	if(o.getClass() == UptimeContainer.class) {
		UptimeContainer oB = (UptimeContainer) o;
		return (oB.creationTime == this.creationTime) &&
			(oB.totalUptime == this.totalUptime);
		} else return false;
	}

	@Override
	public int hashCode() {
		int hash = 7;
		hash = 29 * hash + (int) (this.creationTime ^ (this.creationTime >>> 32));
		hash = 29 * hash + (int) (this.totalUptime ^ (this.totalUptime >>> 32));
		return hash;
	}
}
