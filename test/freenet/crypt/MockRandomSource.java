/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

/**
 * A fake StrongPRNG that is just java.util.Random
 * 
 * @author sdiz
 */
public class MockRandomSource extends RandomSource {
	public MockRandomSource(long seed) {
		setSeed(seed);
	}
	
	@Override
	public int acceptEntropy(EntropySource source, long data, int entropyGuess) {
		return 0;
	}

	@Override
	public int acceptEntropyBytes(EntropySource myPacketDataSource, byte[] buf, int offset, int length, double bias) {
		return 0;
	}

	@Override
	public int acceptTimerEntropy(EntropySource timer) {
		return 0;
	}

	@Override
	public int acceptTimerEntropy(EntropySource fnpTimingSource, double bias) {
		return 0;
	}

	@Override
	public void close() {
	}
}
