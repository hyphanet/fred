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

import junit.framework.TestCase;
import freenet.io.AddressIdentifier;
import freenet.io.AddressIdentifier.AddressType;

/**
 * Test case for the {@link freenet.io.AddressIdentifier} class.
 * 
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id: AddressIdentifierTest.java 10490 2006-09-20 00:07:46Z toad $
 */
public class AddressIdentifierTest extends TestCase {

	public void test() {
		/* test real IPv4 addresses */
		assertEquals(AddressType.IPv4, AddressIdentifier.getAddressType("0.0.0.0"));
		assertEquals(AddressType.IPv4, AddressIdentifier.getAddressType("127.0.0.1"));
		assertEquals(AddressType.IPv4, AddressIdentifier.getAddressType("255.255.255.255"));
		/* in case you didn't know: 183.24.17 = 183.24.0.17 */
		assertEquals(AddressType.IPv4, AddressIdentifier.getAddressType("183.24.17"));
		/* and 127.1 = 127.0.0.1 */
		assertEquals(AddressType.IPv4, AddressIdentifier.getAddressType("127.1"));

		/* test fake IPv4 addresses */
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("192.168.370.12"));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("127.0.0.0.1"));

		/* test real unabridged IPv6 addresses */
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("0:0:0:0:0:0:0:1"));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("fe80:0:0:0:203:dff:fe22:420f"));

		/* test fake IPv6 addresses */
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("1:2:3:4:5:6:7:8:9"));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("12345:6:7:8:9"));
	}

}
