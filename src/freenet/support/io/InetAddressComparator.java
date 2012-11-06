package freenet.support.io;

import java.net.InetAddress;
import java.util.Comparator;

import freenet.support.Fields;

/** Fast non-lexical Comparator for IP addresses for cases where an 
 * attacker might forge IP addresses to try to exhaust a hashtable, so we 
 * need to use a TreeMap, but we don't actually care whether similar IPs 
 * are close together.
 * @author toad */
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
		byte[] bytes0 = arg0.getAddress();
		byte[] bytes1 = arg1.getAddress();
		// Fields.compareBytes doesn't go first by length, so check it here.
		if(bytes0.length > bytes1.length)
			return 1; // IPv6 > IPv4.
		if(bytes1.length > bytes0.length)
			return -1; // IPv4 > IPv6.
		return Fields.compareBytes(bytes0, bytes1);
		// Hostnames in InetAddress are merely cached, equals() only operates on the byte[].
	}

}
