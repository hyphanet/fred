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
