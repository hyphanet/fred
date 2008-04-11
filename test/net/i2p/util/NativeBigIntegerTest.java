package net.i2p.util;

import java.math.BigInteger;
import java.security.SecureRandom;

import net.i2p.util.NativeBigInteger;

import junit.framework.TestCase;

public class NativeBigIntegerTest extends TestCase {
	private final static int NUM_TEST = 5;

	/* the sample numbers are elG generator/prime so we can test with reasonable numbers */
	private final static byte[] _sampleGenerator = new BigInteger("2").toByteArray();
	private final static byte[] _samplePrime = new BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" + "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" + "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" + "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" + "83655D23DCA3AD961C62F356208552BB9ED529077096966D" + "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" + "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" + "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16).toByteArray();

	public void testDoubleValue() {
		SecureRandom rand = new SecureRandom();
		rand.nextBoolean();

		BigInteger jg = new BigInteger(_sampleGenerator);

		int MULTIPLICATOR = 50000; //Run the doubleValue() calls within a loop since they are pretty fast.. 
		int runsProcessed = 0;
		for (runsProcessed = 0; runsProcessed < NUM_TEST; runsProcessed++) {
			NativeBigInteger g = new NativeBigInteger(_sampleGenerator);
			
			double dNative = 0;
			for (int mult = 0; mult < MULTIPLICATOR; mult++)
				dNative = g.doubleValue();
			double jval = 0;
			for (int mult = 0; mult < MULTIPLICATOR; mult++)
				jval = jg.doubleValue();
			
			assertEquals(jval, dNative, Double.MIN_VALUE);
		}
	}

	public void testModPow() {
		// Warming up the random number generator...
		SecureRandom rand = new SecureRandom();
		rand.nextBoolean();

		BigInteger jg = new BigInteger(_sampleGenerator);
		BigInteger jp = new BigInteger(_samplePrime);

		int runsProcessed = 0;
		for (runsProcessed = 0; runsProcessed < NUM_TEST; runsProcessed++) {
			BigInteger bi = new BigInteger(2048, rand);
			NativeBigInteger g = new NativeBigInteger(_sampleGenerator);
			NativeBigInteger p = new NativeBigInteger(_samplePrime);
			NativeBigInteger k = new NativeBigInteger(1, bi.toByteArray());
			
			BigInteger myValue = g.modPow(k, p);
			BigInteger jval = jg.modPow(bi, jp);
						
			assertEquals(jval, myValue);
		}
	}
}
