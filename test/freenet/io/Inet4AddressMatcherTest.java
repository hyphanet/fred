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

import freenet.io.Inet4AddressMatcher;

import junit.framework.TestCase;

/**
 * @author David Roden &lt;droden@gmail.com&gt;
 * @version $Id: Inet4AddressMatcherTest.java 10490 2006-09-20 00:07:46Z toad $
 */
public class Inet4AddressMatcherTest extends TestCase {

	public void test() throws Exception {
		Inet4AddressMatcher matcher = new Inet4AddressMatcher("192.168.1.2");
		assertEquals(false, matcher.matches((Inet4Address) InetAddress.getByName("192.168.1.1")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.168.1.2")));
		assertEquals(false, matcher.matches((Inet4Address) InetAddress.getByName("127.0.0.1")));
		assertEquals(false, matcher.matches((Inet4Address) InetAddress.getByName("0.0.0.0")));
		
		matcher = new Inet4AddressMatcher("192.168.1.2/8");
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.168.1.1")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.168.1.2")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.168.2.1")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.16.81.1")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.255.255.255")));
		assertEquals(false, matcher.matches((Inet4Address) InetAddress.getByName("172.16.1.1")));
		assertEquals(false, matcher.matches((Inet4Address) InetAddress.getByName("127.0.0.1")));
		assertEquals(false, matcher.matches((Inet4Address) InetAddress.getByName("0.0.0.0")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.0.0.0")));

		/* some fancy matching */
		matcher = new Inet4AddressMatcher("192.168.1.1/255.0.255.0");
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.168.1.1")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.16.1.1")));
		assertEquals(false, matcher.matches((Inet4Address) InetAddress.getByName("192.168.2.1")));
		assertEquals(false, matcher.matches((Inet4Address) InetAddress.getByName("192.16.2.1")));
		assertEquals(false, matcher.matches((Inet4Address) InetAddress.getByName("127.0.0.1")));
		
		matcher = new Inet4AddressMatcher("127.0.0.1/8");
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("127.0.0.1")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("127.23.42.64")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("127.0.0.0")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("127.255.255.255")));
		assertEquals(false, matcher.matches((Inet4Address) InetAddress.getByName("28.0.0.1")));

		matcher = new Inet4AddressMatcher("0.0.0.0/0");
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("127.0.0.1")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.168.1.1")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("192.168.2.1")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("172.16.42.23")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("10.0.0.1")));
		assertEquals(true, matcher.matches((Inet4Address) InetAddress.getByName("224.0.0.1")));
	}

}
