package freenet.crypt;

import java.util.Random;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/**
 * Interface for any random-number source in Freenet
 *
 * @author Scott G. Miller <scgmille@indiana.edu>
 */
public abstract class RandomSource extends Random {

    /**
     * Returns a 32 bit random floating point number.  With this method,
     * all possible float values are approximately as likely to occur
     */
    public float nextFullFloat() {
	return Float.intBitsToFloat(nextInt());
    }

    /**
     * Returns a 64 bit random double.  With this method, all possible
     * double values are approximately as likely to occur
     */
    public double nextFullDouble() {
	return Double.longBitsToDouble(nextLong());
    }

    /**
     * Accepts entropy data from a source
     */
    public abstract int acceptEntropy(EntropySource source, long data, int entropyGuess);
    public abstract int acceptTimerEntropy(EntropySource timer);

    /**
     * If entropy estimation is supported, this method will block
     * until the specified number of bits of entropy are available.  If
     * estimation isn't supported, this method will return immediately.
     */
    public void waitForEntropy(int bits) {}

    /**
     * If the RandomSource has any resources it wants to close, it can
     * do so when this method is called
     */
    public abstract void close();


}

