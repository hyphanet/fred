package freenet.transports;

import java.util.Comparator;
import freenet.pluginmanager.PluginAddress;
import freenet.support.Fields;

/**
 * Fast non-lexical Comparator for PluginAddress for cases where an 
 * attacker might forge addresses to try to exhaust a hashtable, so we 
 * need to use a TreeMap, but we don't actually care whether similar addresses 
 * are close together.
 * @author chetan
 * FIXME Remove InetAddressComparator as this handles the same functionality.
 *
 */
public class PluginAddressComparator implements Comparator<PluginAddress> {
	
	public final static PluginAddressComparator COMPARATOR =
			new PluginAddressComparator();

	@Override
	public int compare(PluginAddress o1, PluginAddress o2) {
		if (o1 == o2)
			return 0;

		// By hash code first. Works really fast for IPv4.
		int h1 = o1.hashCode();
		int h2 = o2.hashCode();
		if (h1 > h2)
			return 1;
		else if (h1 < h2)
			return -1;

		byte[] b1 = o1.getBytes();
		byte[] b2 = o2.getBytes();
		// Fields.compareBytes doesn't go first by length, so check it here.
		if (b1.length > b2.length)
			return 1; // e.g. IPv6 > IPv4.
		else if (b1.length < b2.length)
			return -1;
		return Fields.compareBytes(b1, b2);
	}
}
