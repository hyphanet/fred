/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.transport;

import java.net.InetAddress;

public class IPUtil {

	static final boolean strict = true;

	public static boolean isValidAddress(InetAddress i, boolean includeLocalAddressesInNoderefs) {
		if(i.isAnyLocalAddress()) {
			// Wildcard address, 0.0.0.0, ignore.
			return false;
		} else if(i.isLinkLocalAddress() || i.isLoopbackAddress() ||
				i.isSiteLocalAddress()) {
			if(includeLocalAddressesInNoderefs) {
				return true;
			} else return false;
		} else if(i.isMulticastAddress()) {
			// Ignore
			return false;
		} else {
			return true;
		}
	}


}
