package org.spaceroots.mantissa.random;

import java.util.Random;

import freenet.support.Fields;

/** This class implements a powerful pseudo-random number generator
 * developed by Makoto Matsumoto and Takuji Nishimura during
 * 1996-1997.

 * <p>This generator features an extremely long period
 * (2<sup>19937</sup>-1) and 623-dimensional equidistribution up to 32
 * bits accuracy. The home page for this generator is located at <a
 * href="http://www.math.sci.hiroshima-u.ac.jp/~m-mat/MT/emt.html">
 * http://www.math.sci.hiroshima-u.ac.jp/~m-mat/MT/emt.html</a>.</p>

 * <p>This generator is described in a paper by Makoto Matsumoto and
 * Takuji Nishimura in 1998: <a
 * href="http://www.math.sci.hiroshima-u.ac.jp/~m-mat/MT/ARTICLES/mt.pdf">Mersenne
 * Twister: A 623-Dimensionally Equidistributed Uniform Pseudo-Random
 * Number Generator</a>, ACM Transactions on Modeling and Computer
 * Simulation, Vol. 8, No. 1, January 1998, pp 3--30</p>

 * <p>The class is implemented as a specialization of the standard
 * <code>java.util.Random</code> class. This allows to use it in
 * algorithms expecting a standard random generator, and hence benefit
 * from a better generator without code change.</p>

 * <p>This class is mainly a Java port of the 2002-01-26 version of
 * the generator written in C by Makoto Matsumoto and Takuji
 * Nishimura. Here is their original copyright:</p>

 * <table border="0" width="80%" cellpadding="10" align="center" bgcolor="#E0E0E0">
 * <tr><td>Copyright (C) 1997 - 2002, Makoto Matsumoto and Takuji Nishimura,
 *     All rights reserved.</td></tr>

 * <tr><td>Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * <ol>
 *   <li>Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.</li>
 *   <li>Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.</li>
 *   <li>The names of its contributors may not be used to endorse or promote 
 *       products derived from this software without specific prior written 
 *       permission.</li>
 * </ol></td></tr>

 * <tr><td><strong>THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY,
 * OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.</strong></td></tr>
 * </table>

 * @author Makoto Matsumoto and Takuji Nishimura (C version), Luc Maisonobe (Java port)

 * @version $Id: MersenneTwister.java,v 1.1 2005/02/07 23:03:14 amphibian Exp $

 */
public class MersenneTwister
  extends Random {

	private static final long serialVersionUID = -1;
	
  /** Creates a new random number generator.
   * <p>The instance is initialized using the current time as the
   * seed.</p>
   */
  public MersenneTwister() {
    mt = new int[N];
    setSeed(System.currentTimeMillis());
  }

  /** Creates a new random number generator using a single int seed.
   * @param seed the initial seed (32 bits integer)
   */
  public MersenneTwister(int seed) {
    mt = new int[N];
    setSeed(seed);
  }

  /** Creates a new random number generator using an int array seed.
   * @param seed the initial seed (32 bits integers array), if null
   * the seed of the generator will be related to the current time
   */
  public MersenneTwister(int[] seed) {
    mt = new int[N];
    setSeed(seed);
  }

  /** Seed from byte[] */
  public MersenneTwister(byte[] seed) {
	mt = new int[N];
	setSeed(seed);
  }
  
  /** Creates a new random number generator using a single long seed.
   * @param seed the initial seed (64 bits integer)
   */
  public MersenneTwister(long seed) {
    mt = new int[N];
    setSeed(seed);
  }

  /** Reinitialize the generator as if just built with the given int seed.
   * <p>The state of the generator is exactly the same as a new
   * generator built with the same seed.</p>
   * @param seed the initial seed (32 bits integer)
   */
  public void setSeed(int seed) {
    // we use a long masked by 0xffffffffL as a poor man unsigned int
    long longMT = seed;
    mt[0]= (int) longMT;
    for (mti = 1; mti < N; ++mti) {
      // See Knuth TAOCP Vol2. 3rd Ed. P.106 for multiplier.
      // initializer from the 2002-01-09 C version by Makoto Matsumoto
      longMT = (1812433253l * (longMT ^ (longMT >> 30)) + mti) & 0xffffffffL; 
      mt[mti]= (int) longMT;
    }
  }

  /**
   * Seed from byte[].
   */
  public void setSeed(byte[] seed) {
	  int[] seeds = new int[seed.length/4];
	  for(int i=0;i<seeds.length;i+=4) {
		  seeds[i] = Fields.bytesToInt(seed, i);
	  }
	  setSeed(seeds);
  }
  
  /** Reinitialize the generator as if just built with the given int array seed.
   * <p>The state of the generator is exactly the same as a new
   * generator built with the same seed.</p>
   * @param seed the initial seed (32 bits integers array), if null
   * the seed of the generator will be related to the current time
   */
  public void setSeed(int[] seed) {

    if (seed == null) {
      setSeed(System.currentTimeMillis());
      return;
    }

    setSeed(19650218);
    int i = 1;
    int j = 0;

    for (int k = Math.max(N, seed.length); k != 0; k--) {
      long l0 = (mt[i] & 0x7fffffffl)   | ((mt[i]   < 0) ? 0x80000000l : 0x0l);
      long l1 = (mt[i-1] & 0x7fffffffl) | ((mt[i-1] < 0) ? 0x80000000l : 0x0l);
      long l  = (l0 ^ ((l1 ^ (l1 >> 30)) * 1664525l)) + seed[j] + j; // non linear
      mt[i]   = (int) (l & 0xffffffffl);
      i++; j++;
      if (i >= N) {
        mt[0] = mt[N - 1];
        i = 1;
      }
      if (j >= seed.length) {
        j = 0;
      }
    }

    for (int k = N - 1; k != 0; k--) {
      long l0 = (mt[i] & 0x7fffffffl)   | ((mt[i]   < 0) ? 0x80000000l : 0x0l);
      long l1 = (mt[i-1] & 0x7fffffffl) | ((mt[i-1] < 0) ? 0x80000000l : 0x0l);
      long l  = (l0 ^ ((l1 ^ (l1 >> 30)) * 1566083941l)) - i; // non linear
      mt[i]   = (int) (l & 0xffffffffL);
      i++;
      if (i >= N) {
        mt[0] = mt[N - 1];
        i = 1;
      }
    }

    mt[0] = 0x80000000; // MSB is 1; assuring non-zero initial array

  }

  /** Reinitialize the generator as if just built with the given long seed.
   * <p>The state of the generator is exactly the same as a new
   * generator built with the same seed.</p>
   * @param seed the initial seed (64 bits integer)
   */
  public void setSeed(long seed) {
    if (mt == null) {
      // this is probably a spurious call from base class constructor,
      // we do nothing and wait for the setSeed in our own
      // constructors after array allocation
      return;
    }
    setSeed(new int[] { (int) (seed >>> 32), (int) (seed & 0xffffffffl) });
  }

  /** Generate next pseudorandom number.
   * <p>This method is the core generation algorithm. As per
   * <code>java.util.Random</code> contract, it is used by all the
   * public generation methods for the various primitive types {@link
   * #nextBoolean nextBoolean}, {@link #nextBytes nextBytes}, {@link
   * #nextDouble nextDouble}, {@link #nextFloat nextFloat}, {@link
   * #nextGaussian nextGaussian}, {@link #nextInt nextInt} and {@link
   * #nextLong nextLong}.</p>
   * @param bits number of random bits to produce
   */
  protected int next(int bits) {

    int y;

    if (mti >= N) { // generate N words at one time
      int mtNext = mt[0];
      for (int k = 0; k < N - M; ++k) {
        int mtCurr = mtNext;
        mtNext = mt[k + 1];
        y = (mtCurr & 0x80000000) | (mtNext & 0x7fffffff);
        mt[k] = mt[k + M] ^ (y >>> 1) ^ MAG01[y & 0x1];
      }
      for (int k = N - M; k < N - 1; ++k) {
        int mtCurr = mtNext;
        mtNext = mt[k + 1];
        y = (mtCurr & 0x80000000) | (mtNext & 0x7fffffff);
        mt[k] = mt[k + (M - N)] ^ (y >>> 1) ^ MAG01[y & 0x1];
      }
      y = (mtNext & 0x80000000) | (mt[0] & 0x7fffffff);
      mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ MAG01[y & 0x1];

      mti = 0;
    }
  
    y = mt[mti++];

    // tempering
    y ^= (y >>> 11);
    y ^= (y <<   7) & 0x9d2c5680;
    y ^= (y <<  15) & 0xefc60000;
    y ^= (y >>> 18);

    return y >>> (32 - bits);

  }

  private static final int   N     = 624;
  private static final int   M     = 397;
  private static final int[] MAG01 = { 0x0, 0x9908b0df };

  private int[] mt;
  private int   mti;

}
