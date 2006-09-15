/*
  Inet6AddressMatcher.java / Freenet
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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.StringTokenizer;

/**
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id$
 */
public class Inet6AddressMatcher implements AddressMatcher {

	private byte[] address;
	private byte[] netmask;

	public Inet6AddressMatcher(String pattern) {
		if (pattern.indexOf('/') != -1) {
			address = convertToBytes(pattern.substring(0, pattern.indexOf('/')));
			String netmaskString = pattern.substring(pattern.indexOf('/') + 1).trim();
			if (netmaskString.indexOf(':') != -1) {
				netmask = convertToBytes(netmaskString);
			} else {
				netmask = new byte[16];
				int bits = Integer.parseInt(netmaskString);
				for (int index = 0; index < 16; index++) {
					netmask[index] = (byte) (255 << (8 - Math.min(bits, 8)));
					bits = Math.max(bits - 8, 0);
				}
			}
		} else {
			address = convertToBytes(pattern);
			netmask = new byte[16];
			Arrays.fill(netmask, (byte) 0xff);
		}
		if (address.length != 16) {
			throw new IllegalArgumentException("address is not IPv6");
		}
	}

	private byte[] convertToBytes(String address) {
		StringTokenizer addressTokens = new StringTokenizer(address, ":");
		if (addressTokens.countTokens() != 8) {
			throw new IllegalArgumentException(address + " is not an IPv6 address.");
		}
		byte[] addressBytes = new byte[16];
		int count = 0;
		while (addressTokens.hasMoreTokens()) {
			int addressWord = Integer.parseInt(addressTokens.nextToken(), 16);
			addressBytes[count * 2] = (byte) ((addressWord >> 8) & 0xff);
			addressBytes[count * 2 + 1] = (byte) (addressWord & 0xff);
			count++;
		}
		return addressBytes;
	}

	public boolean matches(InetAddress address) {
		byte[] addressBytes = address.getAddress();
		for (int index = 0; index < 16; index++) {
			if ((addressBytes[index] & netmask[index]) != (this.address[index] & netmask[index])) {
				return false;
			}
		}
		return true;
	}

	public static boolean matches(String pattern, Inet6Address address) {
		return new Inet6AddressMatcher(pattern).matches(address);
	}

}
