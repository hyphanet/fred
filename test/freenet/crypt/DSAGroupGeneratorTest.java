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
package freenet.crypt;

import java.math.BigInteger;

import freenet.crypt.DSAGroupGenerator;
import junit.framework.TestCase;

/**
 * Test case for the {@link freenet.crypt.DSAGroupGeneratorTest} class.
 * 
 * @author Florent Daigni&egrave;re &gt;nextgens@freenetproject.org&gt;
 */
public class DSAGroupGeneratorTest extends TestCase {

	public void testIsPrime() { // No need to test below 30 as it won't work anyway
		assertFalse(DSAGroupGenerator.isPrime(BigInteger.ZERO));
		assertFalse(DSAGroupGenerator.isPrime(BigInteger.ONE));
		
		assertTrue(DSAGroupGenerator.isPrime(BigInteger.valueOf(2)));
		assertTrue(DSAGroupGenerator.isPrime(BigInteger.valueOf(1021)));
		
		assertFalse(DSAGroupGenerator.isPrime(BigInteger.valueOf(55)));
	}
}
