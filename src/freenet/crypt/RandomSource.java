/*
  RandomSource.java / Freenet, Java Adaptive Network Client
  Copyright (C) Ian Clarke
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.crypt;

import java.util.Random;

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


}

