/*
  AddressIdentifier.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
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

	public static class AddressType {

		public static final AddressType OTHER = new AddressType("Other");
		public static final AddressType IPv4 = new AddressType("IPv4");
		public static final AddressType IPv6 = new AddressType("IPv6");

		private final String name;

		private AddressType(String name) {
			this.name = name;
		}

		public String toString() {
			return name;
		}

	}

	/**
	 * Tries to detemine the address type of the given address.
	 * 
	 * @param address
	 *            The address to determine the type of
	 * @return {@link AddressType#OTHER} if <code>address</code> is a
	 *         hostname, {@link AddressType#IPv4} or {@link AddressType#IPv6}
	 *         otherwise
	 */
	public static AddressType getAddressType(String address) {
		String byteRegex = "([01]?[0-9]?[0-9]?|2[0-4][0-9]|25[0-5])";
		String ipv4AddressRegex = byteRegex + "\\.(" + byteRegex + "\\.)?(" + byteRegex + "\\.)?" + byteRegex;
		if (Pattern.matches(ipv4AddressRegex, address)) {
			return AddressType.IPv4;
		}
		String wordRegex = "([0-9a-fA-F]{1,4})";
		String ipv6AddressRegex = wordRegex + "?:" + wordRegex + ":" + wordRegex + ":" + wordRegex + ":" + wordRegex + ":" + wordRegex + ":" + wordRegex + ":" + wordRegex;
		if (Pattern.matches(ipv6AddressRegex, address)) {
			return AddressType.IPv6;
		}
		return AddressType.OTHER;
	}

}
