package freenet.crypt;

/**
 * @author amphibian
 * 
 * Not a real RNG at all, just a simple PRNG. Use it for e.g. simulations.
 */
public class DummyRandomSource extends RandomSource {
	static final long serialVersionUID = -1;
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
