package freenet.support.io;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Comparator;

import freenet.support.Fields;

public class InetAddressComparator implements Comparator<InetAddress> {

	public final static InetAddressComparator COMPARATOR =
		new InetAddressComparator();
	
	@Override
	public int compare(InetAddress arg0, InetAddress arg1) {
		// IPv6 > IPv4.
		if(arg0 instanceof Inet4Address && arg1 instanceof Inet6Address)
			return -1;
		if(arg0 instanceof Inet6Address && arg1 instanceof Inet4Address)
			return 1;
		return Fields.compareBytes(arg0.getAddress(), arg1.getAddress());
		// Hostnames in InetAddress are merely cached, equals() only operates on the byte[].
	}

}
