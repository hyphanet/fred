package net.i2p.util;

import java.math.BigInteger;
import java.security.SecureRandom;

import junit.framework.TestCase;

public class NativeBigIntegerTest extends TestCase {
	// Run with <code>ant -Dtest.benchmark=true</code> to do benchmark
	private static final boolean BENCHMARK = Boolean.getBoolean("test.benchmark");
	private static int numRuns = BENCHMARK ? 200 : 5;
	private int runsProcessed;

	/*
	 * the sample numbers are elG generator/prime so we can test with reasonable
	 * numbers
	 */
	private final static byte[] _sampleGenerator = new BigInteger("2").toByteArray();
	private final static byte[] _samplePrime = new BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"
	        + "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245"
	        + "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D"
	        + "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" + "83655D23DCA3AD961C62F356208552BB9ED529077096966D"
	        + "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9"
	        + "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" + "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16)
	        .toByteArray();

	private SecureRandom rand;

	TestIntegers nativeTest;
	TestIntegers javaTest;
	//TestIntegers java7Test;

	protected void setUp() throws Exception {
		if (!NativeBigInteger.isNative())
			printError("can't load native code");

		printInfo("DEBUG: Warming up the random number generator...");
		rand = new SecureRandom();
		rand.nextBoolean();
		printInfo("DEBUG: Random number generator warmed up");

		byte[] randbytes = (new BigInteger(2048, rand)).toByteArray();

		javaTest = new TestIntegers(
			"java",
			new BigInteger(1, _sampleGenerator),
			new BigInteger(1, _samplePrime),
			new BigInteger(1, randbytes)
		);

		nativeTest = new TestIntegers(
			"native",
			new NativeBigInteger(1, _sampleGenerator),
			new NativeBigInteger(1, _samplePrime),
			new NativeBigInteger(1, randbytes)
		);

		/*java7Test = new TestIntegers7(
			"java7",
			new java7.math.BigInteger(_sampleGenerator),
			new java7.math.BigInteger(_samplePrime),
			new java7.math.BigInteger(randbytes)
		);*/

	}

	protected void tearDown() throws Exception {
		if (numRuns == runsProcessed)
			printInfo("INFO: " + runsProcessed + " runs complete without any errors");
		else
			printError("ERROR: " + runsProcessed + " runs until we got an error");

		printInfo(nativeTest.getReport());
		printInfo(javaTest.getReport());
		//printInfo(java7Test.getReport());
		printInfo("native = " + (nativeTest.getTime() * 100.0 / javaTest.getTime()) + "% of pure java time");
		//printInfo("java 7 = " + (java7Test.getTime() * 100.0 / javaTest.getTime()) + "% of pure java time");
	}

	public void testModPow() {
		for (runsProcessed = 0; runsProcessed < numRuns; runsProcessed++) {
			BigInteger nativeVal = nativeTest.modPow();
			BigInteger javaVal = javaTest.modPow();
			//BigInteger java7Val = java7Test.modPow();

			assertEquals(nativeVal, javaVal);
			//assertEquals(javaVal, java7Val);
			//assertEquals(java7Val, nativeVal);
		}
	}

	public void testDoubleValue() {
		for (runsProcessed = 0; runsProcessed < numRuns; runsProcessed++) {
			double nativeVal = nativeTest.doubleValue();
			double javaVal = javaTest.doubleValue();
			//double java7Val = java7Test.doubleValue();

			assertEquals(nativeVal, javaVal);
			//assertEquals(javaVal, java7Val);
			//assertEquals(java7Val, nativeVal);
		}
	}

	private static void printInfo(String info) {
		if (BENCHMARK)
			System.out.println(info);
	}

	private static void printError(String info) {
		if (BENCHMARK)
			System.err.println(info);
	}

	static class TestIntegers {

		final String name;
		final BigInteger g;
		final BigInteger p;
		final BigInteger k;

		protected long time;
		protected int runs;

		final static int DOUBLE_VAL_TEST_RUNS = 0x10000; // Run the doubleValue() calls within a loop since they are pretty fast..

		public TestIntegers(String n, BigInteger g, BigInteger p, BigInteger k) {
			name = n;
			this.g = g;
			this.p = p;
			this.k = k;
		}

		public BigInteger modPow() {
			long start = System.currentTimeMillis();
			BigInteger r = g.modPow(p, k);
			time += System.currentTimeMillis() - start;
			++runs;
			return r;
		}

		public double doubleValue() {
			long start = System.currentTimeMillis();
			double r = 0.0;
			for (int i=0; i < DOUBLE_VAL_TEST_RUNS; ++i) {
				r = g.doubleValue();
			}
			++runs;
			time += System.currentTimeMillis() - start;
			return r;
		}

		public long getTime() {
			return time;
		}

		public String getReport() {
			return name + " run time: \t" + time + "ms (" + (time/runs) + "ms each)";
		}

	}

	/*static class TestIntegers7 extends TestIntegers {

		final java7.math.BigInteger g;
		final java7.math.BigInteger p;
		final java7.math.BigInteger k;

		public TestIntegers7(String name, java7.math.BigInteger g, java7.math.BigInteger p, java7.math.BigInteger k) {
			super(name, null, null, null);
			this.g = g;
			this.p = p;
			this.k = k;
		}

		public BigInteger modPow() {
			long start = System.currentTimeMillis();
			java7.math.BigInteger r = g.modPow(p, k);
			time += System.currentTimeMillis() - start;
			++runs;
			return new BigInteger(r.toByteArray());
		}

		public double doubleValue() {
			long start = System.currentTimeMillis();
			double r = 0.0;
			for (int i=0; i < DOUBLE_VAL_TEST_RUNS; ++i) {
				r = g.doubleValue();
			}
			++runs;
			time += System.currentTimeMillis() - start;
			return r;
		}

	}*/

}
