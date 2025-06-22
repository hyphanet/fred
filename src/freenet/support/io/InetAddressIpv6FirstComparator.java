package freenet.support.io;

import static freenet.node.NodeStats.DEFAULT_MAX_PING_TIME;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Predicate;

import freenet.support.Fields;
import freenet.support.LRUCache;

/** Comparator for IP addresses that sorts IPv6 before IPv4 to enable
 * selecting the first.
 * @author toad */
public class InetAddressIpv6FirstComparator implements Comparator<InetAddress> {

	// need a cache for reachability to avoid doing NlogN issReachable checks in worst case.
	public LRUCache<Integer, Boolean> reachabilityCache = new LRUCache<>(1000, 300000);

	private final Comparator<InetAddress> innerComparator = prefer(Objects::nonNull)
			.thenComparing(preferNot(InetAddress::isAnyLocalAddress))
			.thenComparing(preferNot(InetAddress::isLoopbackAddress))
			.thenComparing(preferNot(InetAddress::isLinkLocalAddress))
			.thenComparing(prefer(this::isReachableSiteLocalAddress))
			.thenComparing(prefer(Inet6Address.class::isInstance))
			.thenComparingInt(Objects::hashCode)
			.thenComparing(InetAddress::getAddress, Fields::compareBytes);

	private boolean isReachableSiteLocalAddress(InetAddress inetAddress) {
		return inetAddress.isSiteLocalAddress() && isReachable(inetAddress);
	}

	public static final InetAddressIpv6FirstComparator COMPARATOR =
		new InetAddressIpv6FirstComparator();

	@Override
	public int compare(InetAddress arg0, InetAddress arg1) {
		if(Objects.equals(arg0, arg1)) {
			return 0;
		}
		return innerComparator.compare(arg0, arg1);
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

	// inverted prefer, because not(...) is only documented since Java 11
	private Comparator<InetAddress> preferNot(Predicate<InetAddress> pred) {
		return (arg0, arg1) -> -1 * predicateToCompare(pred, arg0, arg1);
	}

	private Comparator<InetAddress> prefer(Predicate<InetAddress> pred) {
		return (arg0, arg1) -> predicateToCompare(pred, arg0, arg1);
	}

	private int predicateToCompare(Predicate<InetAddress> pred, InetAddress arg0, InetAddress arg1) {
		if (pred.test(arg0) && pred.test(arg1)) {
				return 0;
			}
			if (pred.test(arg0)) {
				return -1;
			}
			if (pred.test(arg1)) {
				return 1;
			}
			return 0;
	}

}
