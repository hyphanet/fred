package freenet.support.math;

import freenet.support.Fields;

/*
** Originally, we maintained the below functionality as a fork of the above
** code, in the contrib repo. Eventually this was refactored into this class,
** and now we instead depend on upstream mantissa.
**
** There are three milestone commits:
**
** O: 35a37bfad5b42dead835c9f1fb8b0972e730dab2, the earliest version we have in git, imported from svn
** A: 5d16406e4a20c9e6bd9685bf9a8480a13c8acbaf, the latest version we made edits to
** X: aea5c9d491b8de90b3457c0561938ea2ed14ec1d, representing a pristine 7.2 source
**
** You may view the diffs using something like:
**
**  $ git diff -w [S] [T] -- java{,-test}/org/spaceroots/mantissa/random/
**
** As of fred commit e89a2f63e819e8c088a14eaa3c809770db822956, this class, and
** its associated test, re-implements the diff between X-A, by extending
** o.s.m.r.MersenneTwister. Above that, it is also runtime-compatible with any
** version (O,A,X) of it.
**
** This should provide a smooth upgrade path:
**
** # Move users to this class, still running contrib-26 at version A
** # Move contrib to version X (legacy-27).
*/

/**
** This is a synchronized wrapper around {@link org.spaceroots.mantissa.random.MersenneTwister}
** which also adds additional {@code setSeed()} methods.
**
** @author infinity0
*/
public class MersenneTwister extends org.spaceroots.mantissa.random.MersenneTwister {

	private static final long serialVersionUID = 6555069655883958609L;
	
	private byte currentBytes[] = new byte[3];
	private byte currentBytesSize = 0;

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

	/**
	 * Due to bug #0005502 this method must be reimplemented.
	 *  
	 * See https://bugs.freenetproject.org/view.php?id=5502 for details
	 */
	@Override
	public void nextBytes(byte[] bytes) {
		/*
		 * Copy bytes from the currentBytes array if it is not empty
		 */
		int k = 0;
		if (currentBytesSize != 0) {
			if (bytes.length >= currentBytesSize) {
				k = currentBytesSize;
				putKBytes(bytes, currentBytesSize);
			} else {
				putKBytes(bytes, bytes.length);
				return;
			}
		}
		
		/*
		 * Generate a multiple of 4 random bytes
		 */
		int missingBytes = bytes.length - k;
		int noCycles = missingBytes / 4;
		for (int j = 0; j < noCycles; j++)
			for (int rnd = nextInt(), n = 4; n-- > 0; rnd >>= 8) {
				bytes[k++] = (byte)rnd;
			}
		
		/* Add the missing byte(s) to the array and store the rest of the generated
		 * integer in the currentBytes array.
		 */
		missingBytes %= 4;
		if (missingBytes != 0) {
			int rnd = nextInt();
			for (int n = 4; n-- >0; rnd >>= 8) {
				if (k >= bytes.length) {
					currentBytes[currentBytesSize++] = (byte)rnd;
				} else {
					bytes[k++] = (byte)rnd;
				}
			}
		}
	}

	/**
	 * Copy k bytes from the internal array to the output array. 
	 * k must be smaller than the size of the state array!
	 * 
	 * @param bytes - the target array
	 * @param k - number of bytes that will be copied in the target array
	 */
	private void putKBytes(byte[] bytes, int k) {
		for (int i = 0; i < k; i++) {
			bytes[i] = currentBytes[i];
		}
		if (k == currentBytesSize) {
			currentBytesSize = 0;
		} else {
			for (int i = 0; i < currentBytesSize - k; i++) {
				currentBytes[i] = currentBytes[i + k];
			}
			currentBytesSize -= k;
		}
	}
	
}
