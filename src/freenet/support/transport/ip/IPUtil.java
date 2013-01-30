/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.transport.ip;

import java.net.InetAddress;
import java.net.Inet6Address;

public class IPUtil {

	static final boolean strict = true;

	/** Check if address is in site-local range.
	 * [Oracle|Open]JDK up to 8 contains obsolete check for site-local ipv6
	 * addresses, this repaces it with correct one.
	 */
	public static boolean isSiteLocalAddress(InetAddress i) {
	    if(i instanceof Inet6Address) {
			byte [] addr = i.getAddress();
			assert(addr.length == 128/8);
			// XXX what about ipv6-mapped ipv4 site-local addresses?
			// (weird/insane/not-sure-if-possible-but)
			/*
			try {
				if(addr[0] == (byte)0x20 && addr[1] == (byte)0x02) {
					// 2002::/16, 6to4 tunnels
					return InetAddress.getByAddress(
						Arrays.copyOfRange(addr,2,6)).isSiteLocalAddress();
				}
				if(addr[ 0] == (byte)0 && addr[ 1] == (byte)0 &&
				   addr[ 2] == (byte)0 && addr[ 3] == (byte)0 &&
				   addr[ 4] == (byte)0 && addr[ 5] == (byte)0 &&
				   addr[ 6] == (byte)0 && addr[ 7] == (byte)0 &&
				   addr[ 8] == (byte)0 && addr[ 9] == (byte)0 &&
				   addr[10] == (byte)0 && addr[11] == (byte)0) {
					// ::/96, ipv4-compatible ipv6 addresses
					// [DEPRECATED by 2002::/16, probably not worth checking]
					return InetAddress.getByAddress(
						Arrays.copyOfRange(addr,12,16)).isSiteLocalAddress();
				}
			} catch(UnknownHostException e) {
			   return false; // impossible
			}
			*/
			return
				((addr[0] & (byte)0xfe) == (byte)0xfc
				 /* unique local: fc00::/7 */) ||
				(addr[0] == (byte)0xfe && (addr[1] & (byte)0xc0) == (byte)0xc0
				 /* DEPRECATED site local: 0xfec0::/10 */);
	    }
	    return i.isSiteLocalAddress();
	}
        /**
         *
         * @param i
         * @param includeLocalAddressesInNoderefs
         * @return
         */
        public static boolean isValidAddress(InetAddress i, boolean includeLocalAddressesInNoderefs) {
		if(i.isAnyLocalAddress()) {
			// Wildcard address, 0.0.0.0, ignore.
			return false;
		} else if(i.isLinkLocalAddress() || i.isLoopbackAddress() ||
				isSiteLocalAddress(i)) {
			if(includeLocalAddressesInNoderefs) {
				return true;
			} else return false;
		} else if(i.isMulticastAddress()) {
			// Ignore
			return false;
		} else {
			byte[] ipAddressBytes = i.getAddress();
			if(ipAddressBytes.length == 4 && ipAddressBytes[0] == 0) {
				return false;  // First octet of IPv4 address cannot be zero as 0.0.0.0/8 has been reserved since at least RFC790 (also, Java throws an IOException when they're used)
			}
			return true;
		}
	}


}
