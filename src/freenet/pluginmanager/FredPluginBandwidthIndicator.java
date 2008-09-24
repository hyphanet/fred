/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

public interface FredPluginBandwidthIndicator {

	/**
	 * @return the reported upstream bit rate in bits per second. -1 if it's not available. Blocking.
	 */
	public int getUpstramMaxBitRate();

	/**
	 * @return the reported downstream bit rate in bits per second. -1 if it's not available. Blocking.
	 */
	public int getDownstreamMaxBitRate();
}
