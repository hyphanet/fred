/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.util.Random;

/**
 * Interface for any random-number source in Freenet
 *
 * @author Scott G. Miller <scgmille@indiana.edu>
 */
@SuppressWarnings("serial")
public abstract class RandomSource extends Random {

    /**
     * Returns a 32 bit random floating point number. With this method, all possible float values
     * are approximately as likely to occur.
     * 
     * This method may return <tt>NaN</tt>, <tt>+Inf</tt>, <tt>-Inf</tt> or other weird
     * stuff. If you don't know what they are, this method is not for you.
     * 
     * @see RandomSource#nextFloat()
     */
    // FIXME this method is unused, do you *really* want this method?
    public float nextFullFloat() {
    return Float.intBitsToFloat(nextInt());
    }

    /**
     * Returns a 64 bit random double. With this method, all possible double values are
     * approximately as likely to occur.
     * 
     * This method may return <tt>NaN</tt>, <tt>+Inf</tt>, <tt>-Inf</tt> or other weird
     * stuff. If you don't know what they are, this method is not for you.
     * 
     * @see RandomSource#nextDouble()
     */
    // FIXME this method is unused, do you *really* want this method?
    public double nextFullDouble() {
    return Double.longBitsToDouble(nextLong());
    }

    /**
     * Accepts entropy data from a source
     */
    public abstract int acceptEntropy(EntropySource source, long data, int entropyGuess);
    /**
     * Accepts entropy in the form of timing data from a source
     */
    public abstract int acceptTimerEntropy(EntropySource timer);
    
    /**
     * Accept entropy from a source with a bias
     * @param bias Value by which we multiply the entropy before counting it.
     * Must be <= 1.0.
     */
    public abstract int acceptTimerEntropy(EntropySource fnpTimingSource, double bias);

    /**
     * Accepts larger amounts of entropy data from a source, with a bias
     * @param myPacketDataSource The source from which the data has come.
     * @param buf The buffer to read bytes from.
     * @param offset The offset to start reading from.
     * @param length The number of bytes to read.
     * @param bias The bias. Value by which we multiply the entropy before counting it.
     * Must be <= 1.0.
     */
    public abstract int acceptEntropyBytes(EntropySource myPacketDataSource, byte[] buf, int offset, int length, double bias);
    
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
    
    @Override
    protected synchronized int next(int bits) {
        return super.next(bits);
    }
}

