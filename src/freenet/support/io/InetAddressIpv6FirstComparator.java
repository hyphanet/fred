package freenet.support.io;

import java.net.InetAddress;
import java.util.Comparator;

import freenet.support.Fields;

/** Comparator for IP addresses that sorts IPv6 before IPv4 to enable
 * selecting the first.
 * @author toad */
public class InetAddressIpv6FirstComparator implements Comparator<InetAddress> {

	public final static InetAddressIpv6FirstComparator COMPARATOR =
		new InetAddressIpv6FirstComparator();

	@Override
	public int compare(InetAddress arg0, InetAddress arg1) {
		if(arg0 == arg1) return 0;
		byte[] bytes0 = arg0.getAddress();
		byte[] bytes1 = arg1.getAddress();
		if(bytes0.length > bytes1.length)
			return -1; // IPv6 < IPv4. => prefer IPv6 over IPv4
		if(bytes1.length > bytes0.length)
			return 1; // IPv4 < IPv6.
		int a = arg0.hashCode();
		int b = arg1.hashCode();
		// By hash code first. Works really fast for IPv4.
		if(a > b) return 1;
		else if(b > a) return -1;
		return Fields.compareBytes(bytes0, bytes1);
		// Hostnames in InetAddress are merely cached, equals() only operates on the byte[].
	}

}
