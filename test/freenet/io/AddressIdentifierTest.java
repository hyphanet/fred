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

import static org.junit.Assert.*;

import org.junit.Test;

import freenet.io.AddressIdentifier.AddressType;

/**
 * Test case for the {@link freenet.io.AddressIdentifier} class.
 * 
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id: AddressIdentifierTest.java 10490 2006-09-20 00:07:46Z toad $
 */
public class AddressIdentifierTest {

	@Test
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
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("0:0:0:0:0:0:0:1", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("fe80:0:0:0:203:dff:fe22:420f", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("FE80:0:0:0:203:DFF:FE22:420F", false));

		/* test real abridged IPv6 addresses */
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("::1:2:3:4:5:6:7", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("::1:2:3:4:5:6", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("::1:2:3:4:5", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("::1:2:3:4", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("::1:2:3", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("::1:2", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("::1", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("::", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1::2:3:4:5:6:7", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1::2:3:4:5:6", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1::2:3:4:5", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1::2:3:4", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1::2:3", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1::2", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1::", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2::3:4:5:6:7", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2::3:4:5:6", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2::3:4:5", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2::3:4", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2::3", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2::", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3::4:5:6:7", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3::4:5:6", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3::4:5", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3::4", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3::", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3:4::5:6:7", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3:4::5:6", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3:4::5", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3:4::", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3:4:5::6:7", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3:4:5::6", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3:4:5::", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3:4:5:6::7", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3:4:5:6::", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("1:2:3:4:5:6:7::", false));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("fe80::203:dff:fe22:420f%10", true));
		assertEquals(AddressType.IPv6, AddressIdentifier.getAddressType("FE80::203:DFF:FE22:420F%10", true));

		/* test fake IPv6 addresses */
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("1:2:3:4:5:6:7:8:9", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("12345:6:7:8:9", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("1:2:3:4:5:6:7:8%10", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType(":1:2:3:4:5:6:7:8", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType(":1:2:3:4:5:6:7", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("1:2:3:4:5:6:7:", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType(":1:2::3:4", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("1:2::3:4:", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("1::2:3:4:5:6:7:8:9", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("::1::2:3", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("1::2:3::", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("1::2:3::4", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("12:34:56:78:9a:bc:de:fg", false));
		assertEquals(AddressType.OTHER, AddressIdentifier.getAddressType("fe80::203:dff:fe22:420f%a", true));
	}
	
	@Test
	public void testIsAnISATAPIPv6Address() {
		assertFalse(AddressIdentifier.isAnISATAPIPv6Address("fe80:0:0:0:203:dff:fe22:420f"));
		assertFalse(AddressIdentifier.isAnISATAPIPv6Address("fe80:0:5efe:0:203:dff:fe22:420f"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("2001:1:2:3:0:5efe:c801:20a"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("2001:1:2:3:0:5efe:c801:20a%10"));
		// Some abridged addresses
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("::1:2:3:0:5efe:6:7"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("::1:2:0:5efE:5:6"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("::1:0:5eFe:4:5"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("::0:5eFE:3:4"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("::5Efe:2:3"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1::2:3:0:5EfE:6:7"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1::2:0:5EFe:5:6"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1::0:5EFE:4:5"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1::5efe:3:4"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1:2::3:0:5efe:6:7"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1:2::0:5efe:5:6"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1:2::5efe:4:5"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1:2:3::0:5efe:6:7"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1:2:3::5efe:5:6"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1:2:3:4::5efe:6:7"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1:2:3:4:0:5efe::7"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1:2:3:4:0:5efe::"));
		assertTrue(AddressIdentifier.isAnISATAPIPv6Address("1:2:3:4:0:5efe:7::"));
		// Some invalid addresses
		assertFalse(AddressIdentifier.isAnISATAPIPv6Address("2001:1:2:3:5efe:c801:20a"));
		assertFalse(AddressIdentifier.isAnISATAPIPv6Address("2001:1:2:3:4:5:5efe:c801:20a"));
		assertFalse(AddressIdentifier.isAnISATAPIPv6Address("2001::1::5efe:c801:20a"));
		assertFalse(AddressIdentifier.isAnISATAPIPv6Address("1:2:3:0:5efe::"));
	}

}
