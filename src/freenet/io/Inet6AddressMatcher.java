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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.StringTokenizer;

import freenet.io.AddressIdentifier.AddressType;

/**
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id$
 */
public class Inet6AddressMatcher implements AddressMatcher {
	public AddressType getAddressType() {
		return AddressType.IPv6;
	}

	private static final byte[] FULL_MASK = new byte[16];
	static {
		Arrays.fill(FULL_MASK, (byte) 0xff);
	}
	
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
			netmask = FULL_MASK;
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

	public String getHumanRepresentation() {
		if(netmask == FULL_MASK)
			return convertToString(address);
		else
			return convertToString(address)+'/'+convertToString(netmask);
	}

	private String convertToString(byte[] addr) {
		StringBuffer sb = new StringBuffer(4*8+7);
		for(int i=0;i<8;i++) {
			if(i != 0) sb.append(':');
			int token = ((addr[i*2] & 0xff) << 8) + (addr[i*2+1] & 0xff);
			sb.append(Integer.toHexString(token));
		}
		return sb.toString();
	}

}
