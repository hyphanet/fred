package freenet.support.io;

import static freenet.node.NodeStats.DEFAULT_MAX_PING_TIME;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Comparator;

import freenet.support.Fields;
import freenet.support.LRUCache;

/** Comparator for IP addresses that sorts IPv6 before IPv4 to enable
 * selecting the first.
 * @author toad */
public class InetAddressIpv6FirstComparator implements Comparator<InetAddress> {

	// need a cache for reachability to avoid doing NlogN issReachable checks in worst case.
	public LRUCache<Integer, Boolean> reachabilityCache = new LRUCache<>(1000, 300000);

	public static final InetAddressIpv6FirstComparator COMPARATOR =
		new InetAddressIpv6FirstComparator();

	@Override
	public int compare(InetAddress arg0, InetAddress arg1) {
		if ((arg0 == null && arg1 == null)) return 0;
		// prefer non-null over null
		if (arg0 == null) return 1;
		if (arg1 == null) return -1;
		if(arg0.equals(arg1)) return 0;
		// prefer everything to broadcast
		if (!arg0.isAnyLocalAddress() && arg1.isAnyLocalAddress()) {
			return -1;
		} else if (arg0.isAnyLocalAddress() && !arg1.isAnyLocalAddress()) {
			return 1;
		}
		// prefer everything over loopback / localhost
		if (!arg0.isLoopbackAddress() && arg1.isLoopbackAddress()) {
			return -1;
		} else if (arg0.isLoopbackAddress() && !arg1.isLoopbackAddress()) {
			return 1;
		}
		// prefer LAN routable addresses over link-local addresses because most of these will not work
		if (!arg0.isLinkLocalAddress() && arg1.isLinkLocalAddress()) {
			return -1;
		} else if (arg0.isLinkLocalAddress() && !arg1.isLinkLocalAddress()) {
			return 1;
		}
		// prefer LAN addresses *if they are routable* over global addresses, because they should stay within VPNs
		if (!arg0.isSiteLocalAddress() && arg1.isSiteLocalAddress()) {
			// prefer reachable over unreachable addresses. This is usually a ping. TODO: This actually pings all advertised ip addresses. Is that OK? Do we need a maximum number of accepted addresses to prevent abuse as DDoS? 
			boolean reachable = isReachable(arg1);
			if (reachable) {
				return 1;
			}
		} else if (arg0.isSiteLocalAddress() && !arg1.isSiteLocalAddress()) {
			boolean reachable = isReachable(arg0);
			if (reachable) {
				return -1;
			}
		}
		byte[] bytes0 = arg0.getAddress();
		byte[] bytes1 = arg1.getAddress();
		// prefer IPv6 over IPv4
		if(bytes0.length > bytes1.length) {
			return -1;
		} else if(bytes1.length > bytes0.length) {
			return 1;
		}

		// calculate hashCode only once; used multiple times later
		int hashCode0 = arg0.hashCode();
		int hashCode1 = arg1.hashCode();
		// Sort by hash code as fallback. This is fast.
		if(hashCode0 > hashCode1) return 1;
		else if(hashCode1 > hashCode0) return -1;
		return Fields.compareBytes(bytes0, bytes1);
		// Hostnames in InetAddress are merely cached, equals() only operates on the byte[].
	}

	private boolean isReachable(InetAddress inetAddress) {
		int hashCode = inetAddress.hashCode();
		Boolean reachable = reachabilityCache.get(hashCode);
		if (reachable == null) {
			try {
				reachable = inetAddress.isReachable((int) DEFAULT_MAX_PING_TIME);
			} catch (IOException e) {
				reachable = false;
			}
			reachabilityCache.put(hashCode, reachable);
		}
		return reachable;
	}

}
