/*
  DummyRandomSource.java / Freenet
  Copyright (C) amphibian
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

/**
 * @author amphibian
 * 
 * Not a real RNG at all, just a simple PRNG. Use it for e.g. simulations.
 */
public class DummyRandomSource extends RandomSource {
	private static final long serialVersionUID = -1;
    public int acceptEntropy(EntropySource source, long data, int entropyGuess) {
        return 0;
    }

    public int acceptTimerEntropy(EntropySource timer) {
        return 0;
    }

    public int acceptTimerEntropy(EntropySource fnpTimingSource, double bias) {
        return 0;
    }

    public int acceptEntropyBytes(EntropySource myPacketDataSource, byte[] buf,
            int offset, int length, double bias) {
        return 0;
    }

    public void close() {
    }

}
