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

	public Inet6AddressMatcher(String pattern) throws IllegalArgumentException {
		if (pattern.indexOf('/') != -1) {
			address = convertToBytes(pattern.substring(0, pattern.indexOf('/')));
			String netmaskString = pattern.substring(pattern.indexOf('/') + 1).trim();
			if (netmaskString.indexOf(':') != -1) {
				netmask = convertToBytes(netmaskString);
			} else {
				netmask = new byte[16];
				int bits = Integer.parseInt(netmaskString);
				if (bits > 128 || bits < 0)
					throw new IllegalArgumentException("Mask bits out of range: " + bits + " (" + netmaskString + ")");
				for (int index = 0; index < 16; index++) {
					netmask[index] = (byte) (255 << (8 - Math.min(bits, 8)));
					bits = Math.max(bits - 8, 0);
				}
			}
			if(Arrays.equals(netmask, FULL_MASK)) netmask = FULL_MASK;
		} else {
			address = convertToBytes(pattern);
			netmask = FULL_MASK;
		}
		if (address.length != 16) {
			throw new IllegalArgumentException("address is not IPv6");
		}
	}

	private byte[] convertToBytes(String address) throws IllegalArgumentException {
		String[] addressTokens = address.split(":", -1); // Don't let Java discard trailing empty string, to distinguish "1:2:3:4:5:6:7:" and "1:2:3:4:5:6:7::" 
		int tokenPosition = 0;
		byte[] addressBytes = new byte[16]; // Part before ::
		byte[] addressBytesEnd = new byte[16]; // Part after ::, in reverse order
		int count = 0;
		int endCount = -1;
		if(address.startsWith(":")) {
			if(address.startsWith("::")) {
				if(address == "::") {
					return addressBytes; // Return 0:0:0:0:0:0:0:0
				}
				tokenPosition=2; // This is an empty token, could be mistaken as duplicate ::
				endCount = 0;
			} else {
				throw new IllegalArgumentException(address + " is not an IPv6 address.");
			}
		}
		while (tokenPosition < addressTokens.length) {
			String token = addressTokens[tokenPosition];
			tokenPosition++;
			if(!token.isEmpty() ) {
				int addressWord;
				try {
					addressWord = Integer.parseInt(token, 16);
				} catch(NumberFormatException e) {
					throw new IllegalArgumentException(address + " is not an IPv6 address.");
				}
				// Enforce limits
				if(addressWord < 0 || addressWord > 0xffff) {
					throw new IllegalArgumentException(address + " is not an IPv6 address.");
				}
				if(endCount == -1) {
					if(count >= 8) {
						throw new IllegalArgumentException(address + " is not an IPv6 address.");
					}
					addressBytes[count * 2] = (byte) ((addressWord >> 8) & 0xff);
					addressBytes[count * 2 + 1] = (byte) (addressWord & 0xff);
					count++;
				} else {
					if(count + endCount >= 7) {
						throw new IllegalArgumentException(address + " is not an IPv6 address.");
					}
					addressBytesEnd[endCount * 2] = (byte) ((addressWord >> 8) & 0xff);
					addressBytesEnd[endCount * 2 + 1] = (byte) (addressWord & 0xff);
					endCount++;
				}
			} else if(endCount == -1) {
				if(count >= 8 || tokenPosition == addressTokens.length) {
					// Catch the case "1:2:3:4:5:6:7:8::", let "1:2:3:4:5:6:7::" go
					throw new IllegalArgumentException(address + " is not an IPv6 address.");
				}
				endCount = 0;
			} else if(endCount > 0 || tokenPosition != addressTokens.length){ 
				throw new IllegalArgumentException(address + " is not an IPv6 address.");
			}
		}
		if(endCount != -1) { // Copy the end part to the end of main part
			for(int index = count; index < 8 - endCount; index++) {
				addressBytes[index * 2] = 0;
				addressBytes[index * 2 + 1] = 0;
			}
			for(int index = 0; index < endCount; index++) {
				addressBytes[(8 - endCount + index) * 2] = addressBytesEnd[index * 2];
				addressBytes[(8 - endCount + index) * 2 + 1] = addressBytesEnd[index * 2 + 1];
			}
		}
		return addressBytes;
	}

	@Override
	public boolean matches(InetAddress address) {
		if (!(address instanceof Inet6Address)) return false;
		byte[] addressBytes = address.getAddress();
		for (int index = 0; index < 16; index++) {
			if ((addressBytes[index] & netmask[index]) != (this.address[index] & netmask[index])) {
				return false;
			}
		}
		return true;
	}

	public static boolean matches(String pattern, InetAddress address) throws IllegalArgumentException {
		return new Inet6AddressMatcher(pattern).matches(address);
	}

	@Override
	public String getHumanRepresentation() {
		if(netmask == FULL_MASK)
			return convertToString(address);
		else
			return convertToString(address)+'/'+convertToString(netmask);
	}

	private String convertToString(byte[] addr) {
		StringBuilder sb = new StringBuilder(4*8+7);
		for(int i=0;i<8;i++) {
			if(i != 0) sb.append(':');
			int token = ((addr[i*2] & 0xff) << 8) + (addr[i*2+1] & 0xff);
			sb.append(Integer.toHexString(token));
		}
		return sb.toString();
	}

}
