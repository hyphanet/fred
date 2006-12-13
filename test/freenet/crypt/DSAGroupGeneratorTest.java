package test.freenet.crypt;

import java.math.BigInteger;

import freenet.crypt.DSAGroupGenerator;
import junit.framework.TestCase;

public class DSAGroupGeneratorTest extends TestCase {

	public void testIsPrime() {
		assertFalse(DSAGroupGenerator.isPrime(BigInteger.ZERO));
		assertFalse(DSAGroupGenerator.isPrime(BigInteger.ONE));
		
		assertTrue(DSAGroupGenerator.isPrime(BigInteger.valueOf(2)));
		assertTrue(DSAGroupGenerator.isPrime(BigInteger.valueOf(3)));
		assertTrue(DSAGroupGenerator.isPrime(BigInteger.valueOf(1029)));
		
		assertFalse(DSAGroupGenerator.isPrime(BigInteger.valueOf(55)));
	}
}
