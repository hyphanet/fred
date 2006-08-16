package freenet.transport;

import java.net.InetAddress;
import java.util.StringTokenizer;

import freenet.support.Logger;

public class IPUtil {

	static final boolean strict = true;

	/**
	 * Is this a valid address? Specifically, return false if it
	 * is in an RFC3330 reserved space.
	 */
	public static boolean checkAddress(int[] i) {
		// ip address (IPV6 is not supported by this transport)
		boolean logDEBUG = Logger.shouldLog(Logger.DEBUG,IPUtil.class);
		if(logDEBUG)
			Logger.debug(IPUtil.class, "Checking "+i[0]+"."+i[1]+"."+i[2]+
					"."+i[3]);
		if (i.length != 4)
			return false;

		for (int j = 0 ; j < 4 ; j++)
			if ((i[j] < 0) || (i[j] > 255))
				return false;

		if ((i[0] == 10) || ((i[0] == 172) && (i[1] >= 16) && (i[1] < 31)) 
				|| ((i[0] == 192) && (i[1] == 168))) // local network
			return false;

		if ((i[0] == 169) && (i[1] == 254)) 
			return false; // link local

		if ((i[0] == 198) && ((i[1] == 18) || (i[1] == 19)))
			return false; // RFC2544

		if ((i[0] == 192) && (i[1] == 0) && (i[2] == 2))
			return false; // test-net, see RFC3330

		if (i[0] == 127) // loopback
			return false;

		if (i[0] == 0) // "this" net
			return false;

		if ((i[0] >= 224) && (i[0] < 240))
			return false; // multicast

		return true;
	}

	public static boolean checkAddress(InetAddress addr) {
		if(Logger.shouldLog(Logger.DEBUG,IPUtil.class)) Logger.debug(IPUtil.class, "Checking "+addr);
		int[] i = new int[4];
		byte[] bytes = addr.getAddress();
		if(bytes.length != 4) {
			return false;
		}
		for(int x=0;x<4;x++) {
			byte b = bytes[x];
			int ii = b;
			if(ii < 0) ii += 256;
			i[x] = ii;
		}
		return checkAddress(i);
	}

	public static boolean checkAddress(byte[] b) {
		int[] i = new int[4];
		for(int x=0;x<4;x++) i[x] = b[x] & 0xff;
		return checkAddress(i);
	}

	public static boolean checkAddress(String s) {
		return checkAddress(s, false);
	}

	public static boolean checkAddress(String s, boolean noPort) {
		boolean logDEBUG = Logger.shouldLog(Logger.DEBUG,IPUtil.class);
		if(logDEBUG)
			Logger.debug(IPUtil.class, "Checking "+s);
		String a = s;
		if(!noPort) {
			StringTokenizer st = new StringTokenizer(s, ":");
			if (st.countTokens() != 2) 
				return false;

			a = st.nextToken();
			try {
				int p = Integer.parseInt(st.nextToken());
				if ((p < 0) || (p >= (1 << 16)))
					return false;
			} catch (NumberFormatException e) {
				return false;
			}
		}
		if (!strict)
			return true;

		// strict check
		if(logDEBUG)
			Logger.debug(IPUtil.class, "Strict check");

		StringTokenizer at = new StringTokenizer(a, ".");
		int n = at.countTokens();

		try {
			int[] i = new int[4];
			for (int j = 0 ; j < 4 ; j++) {
				if (!at.hasMoreTokens()) {
					if(logDEBUG)
						Logger.debug(IPUtil.class, "Only "+j+" tokens.");
					return false;
				}
				String tok = at.nextToken();
				if(logDEBUG)
					Logger.debug(IPUtil.class, "Trying to parseInt: "+tok);
				i[j] = Integer.parseInt(tok);
			}
			return checkAddress(i);
		} catch (NumberFormatException e) {
			// dns address
			if (n < 2) {
				Logger.minor(IPUtil.class, a+": Not a DNS address, too short!");
				return false;
			}

			if(logDEBUG)
				Logger.debug(IPUtil.class, "Apparently valid DNS address: "+a);
			return true;
			// maybe we should actually look up the IP address here,
			// but I'm concerned about revealing ourselves.
		}
	}


}
