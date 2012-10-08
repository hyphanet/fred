package freenet.support.io;

import java.net.InetAddress;
import java.util.Comparator;

import freenet.support.Fields;

public class InetAddressComparator implements Comparator<InetAddress> {

	public final static InetAddressComparator COMPARATOR =
		new InetAddressComparator();
	
	@Override
	public int compare(InetAddress arg0, InetAddress arg1) {
		if(arg0 == arg1) return 0;
		int a = arg0.hashCode();
		int b = arg1.hashCode();
		// By hash code first. Works really fast for IPv4.
		if(a > b) return 1;
		else if(b > a) return -1;
		return Fields.compareBytes(arg0.getAddress(), arg1.getAddress());
		// Hostnames in InetAddress are merely cached, equals() only operates on the byte[].
	}

}
