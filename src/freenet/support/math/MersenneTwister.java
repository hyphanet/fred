package freenet.support.math;

import freenet.support.Fields;

/**
** This is a synchronized wrapper around {@link org.spaceroots.mantissa.random.MersenneTwister}
** which also adds additional {@code setSeed()} methods.
**
** @author infinity0
*/
public class MersenneTwister extends org.spaceroots.mantissa.random.MersenneTwister {

	private static final long serialVersionUID = 6555069655883958609L;

	/** Creates a new random number generator using the current time as the seed. */
	public MersenneTwister() { super(); }

	/** Creates a new random number generator using a single int seed. */
	public MersenneTwister(int seed) { super(seed); }

	/** Creates a new random number generator using an int array seed. */
	public MersenneTwister(int[] seed) { super(seed); }

	/** Creates a new random number generator using a single long seed. */
	public MersenneTwister(long seed) { super(seed); }

	/** Creates a new random number generator using a byte array seed. */
	public MersenneTwister(byte[] seed) {
		super(Fields.bytesToInts(seed, 0, seed.length));
	}

	/** {@inheritDoc} */
	@Override public synchronized void setSeed(int seed) { super.setSeed(seed); }

	/** {@inheritDoc} */
	@Override public synchronized void setSeed(int[] seed) { super.setSeed(seed); }

	/** {@inheritDoc} */
	@Override public synchronized void setSeed(long seed) { super.setSeed(seed); }

	/**
	** Reinitialize the generator as if just built with the given byte array seed.
	** <p>The state of the generator is exactly the same as a new
	** generator built with the same seed.</p>
	** @param seed the initial seed (8 bits byte array), if null
	** the seed of the generator will be related to the current time
	*/
	public synchronized void setSeed(byte[] seed) {
		super.setSeed(Fields.bytesToInts(seed, 0, seed.length));
	}

	/** {@inheritDoc} */
	@Override protected synchronized int next(int bits) { return super.next(bits); }

}
