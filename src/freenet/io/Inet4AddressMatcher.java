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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.StringTokenizer;

import freenet.io.AddressIdentifier.AddressType;

/**
 * Matcher for IPv4 network addresses. It works like the regex matcher in
 * {@link java.util.regex.Matcher}, i.e. you create a new Inet4AddressMatcher
 * with the IP address pattern and can then match IP addresses to it. The
 * Inet4AddressMatcher can match the following kinds of IP addresses or address
 * ranges:
 * <ul>
 * <li>IP address only (<code>192.168.1.2</code>)</li>
 * <li>IP address and network mask (<code>192.168.1.2/255.255.255.0</code>)</li>
 * <li>IP address and network mask bits (<code>192.168.1.2/24</code>)</li>
 * </ul>
 * 
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id$
 */
public class Inet4AddressMatcher implements AddressMatcher {
	public AddressType getAddressType() {
		return AddressType.IPv4;
	}

	/** The address of this matcher */
	private int address;

	/** The network mask of this matcher */
	private int networkMask;

	/**
	 * Creates a new address matcher that matches InetAddress objects to the
	 * address specification given by <code>cidrHostname</code>.
	 * 
	 * @param cidrHostname
	 *            The address range this matcher matches
	 */
	public Inet4AddressMatcher(String cidrHostname) {
		int slashPosition = cidrHostname.indexOf('/');
		if (slashPosition == -1) {
			address = convertToBytes(cidrHostname);
			networkMask = 0xffffffff;
		} else {
			address = convertToBytes(cidrHostname.substring(0, slashPosition));
			String maskPart = cidrHostname.substring(slashPosition + 1);
			if (maskPart.indexOf('.') == -1) {
				networkMask = 0xffffffff << (32 - Integer.parseInt(maskPart));
				if (Integer.parseInt(maskPart) == 0) {
					networkMask = 0;
				}
			} else {
				networkMask = convertToBytes(maskPart);
			}
		}
	}

	/**
	 * Converts a dotted IP address (a.b.c.d) to a 32-bit value. The first octet
	 * will be in bits 24 to 31, the second in bits 16 to 23, the third in bits
	 * 8 to 15, and the fourth in bits 0 to 7.
	 * 
	 * @param address
	 *            The address to convert
	 * @return The IP address as 32-bit value
	 * @throws NumberFormatException
	 *             if a part of the string can not be parsed using
	 *             {@link Integer#parseInt(java.lang.String)}
	 * @throws java.util.NoSuchElementException
	 *             if <code>address</code> contains less than 3 dots
	 */
	public static int convertToBytes(String address) {
		StringTokenizer addressTokens = new StringTokenizer(address, ".");
		int bytes = Integer.parseInt(addressTokens.nextToken()) << 24 | Integer.parseInt(addressTokens.nextToken()) << 16 | Integer.parseInt(addressTokens.nextToken()) << 8 | Integer.parseInt(addressTokens.nextToken());
		return bytes;
	}

	/**
	 * Checks whether the given address matches this matcher's address.
	 * 
	 * @param inetAddress
	 *            The address to match to this matcher
	 * @return <code>true</code> if <code>inetAddress</code> matches the
	 *         specification of this matcher, <code>false</code> otherwise
	 */
	public boolean matches(InetAddress inetAddress) {
		int matchAddress = convertToBytes(inetAddress.getHostAddress());
		return (matchAddress & networkMask) == (address & networkMask);
	}

	/**
	 * Shortcut method for creating a new Inet4AddressMatcher and matching
	 * <code>address</code> to it.
	 * 
	 * @param cidrHostname
	 *            The host specification to match
	 * @param address
	 *            The address to match
	 * @return <code>true</code> if <code>address</code> matches the
	 *         specification in <code>cidrHostname</code>, <code>false</code>
	 *         otherwise
	 * @see #Inet4AddressMatcher(String)
	 * @see #matches(Inet4Address)
	 */
	public static boolean matches(String cidrHostname, Inet4Address address) {
		return new Inet4AddressMatcher(cidrHostname).matches(address);
	}

}
