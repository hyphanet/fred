/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.io;

import java.util.regex.Pattern;

/**
 * Identifies numeric IP addresses. This class is currently capable of
 * recognizing:
 * <ul>
 * <li>IPv4 unabridged (a.b.c.d)</li>
 * <li>IPv4 abridged (a.b.d or a.d)</li>
 * <li>IPv6 unabridged (a:b:c:d:e:f:g:h)</li>
 * </ul>
 * 
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id$
 */
public class AddressIdentifier {
	public static final Pattern ipv4Pattern, ipv6Pattern, ipv6PatternWithPercentScopeID, ipv6ISATAPPattern;
	
	static {
		String byteRegex = "([01]?[0-9]?[0-9]?|2[0-4][0-9]|25[0-5])";
		String ipv4AddressRegex = byteRegex + "\\.(" + byteRegex + "\\.)?(" + byteRegex + "\\.)?" + byteRegex;
		ipv4Pattern = Pattern.compile(ipv4AddressRegex);
		
		String wordRegex = "([0-9a-fA-F]{1,4})";
		String percentScopeIDRegex = "(?:%[0-9]{1,3})?";
		String ipv6AddressRegex = wordRegex + "?:" + wordRegex + ':' + wordRegex + ':' + wordRegex + ':' + wordRegex + ':' + wordRegex + ':' + wordRegex + ':' + wordRegex;
		String ipv6ISATAPAddressRegex = wordRegex + "?:" + wordRegex + ':' + wordRegex + ':' + wordRegex + ":(0){1,4}:5(efe|EFE):" + wordRegex + ':' + wordRegex + percentScopeIDRegex;
		ipv6Pattern = Pattern.compile(ipv6AddressRegex);
		ipv6PatternWithPercentScopeID = Pattern.compile(ipv6AddressRegex + percentScopeIDRegex);
		ipv6ISATAPPattern = Pattern.compile(ipv6ISATAPAddressRegex);
	}
	
	public enum AddressType {
		OTHER, IPv4, IPv6;
	}

	/**
	 * Tries to detemine the address type of the given address.
	 * 
	 * REDFLAG: IPv6 percent scope ID's could cause problems with URI's.
	 * Should not be exploitable as we don't do anything important with 
	 * URI's with hosts in anyway. In particular we MUST NOT do anything 
	 * with hosts from URI's from untrusted sources e.g. content filter.
	 * But then that would be completely stupid, so we don't.
	 * 
	 * @param address
	 *            The address to determine the type of
	 * @return {@link AddressType#OTHER} if <code>address</code> is a
	 *         hostname, {@link AddressType#IPv4} or {@link AddressType#IPv6}
	 *         otherwise
	 */
	public static AddressType getAddressType(String address) {
		return AddressIdentifier.getAddressType(address,true);
	}

	/**
	 * Tries to detemine the address type of the given address.
	 * 
	 * @param address
	 *            The address to determine the type of
	 * @param allowIPv6PercentScopeID
	 *            If true, match %<scope-id> suffixed IPv6 IP addresses
	 * @return {@link AddressType#OTHER} if <code>address</code> is a
	 *         hostname, {@link AddressType#IPv4} or {@link AddressType#IPv6}
	 *         otherwise
	 */
	public static AddressType getAddressType(String address, boolean allowIPv6PercentScopeID) {
		if (ipv4Pattern.matcher(address).matches()) {
			return AddressType.IPv4;
		}else if ((allowIPv6PercentScopeID ? ipv6PatternWithPercentScopeID : ipv6Pattern).matcher(address).matches()) {
			return AddressType.IPv6;
		}
		return AddressType.OTHER;
	}

	/**
	 * @see http://www.ietf.org/rfc/rfc4214.txt
	 */
	public static boolean isAnISATAPIPv6Address(String address) {
		return ipv6ISATAPPattern.matcher(address).matches();
	}
}
