package freenet.support;

public abstract class BloomFilter {
	/**
	 * Calculate optimal K value
	 * 
	 * @param filterLength
	 *            filter length in bits
	 * @param maxKey
	 * @return optimal K
	 */
	public static int optimialK(int filterLength, long maxKey) {
		long k = Math.round(Math.log(2) * filterLength / maxKey);

		if (k < 1)
			k = 1;
		if (k > 32)
			k = 32;

		return (int) k;
	}

	public abstract void addKey(byte[] key);

	public abstract void removeKey(byte[] key);

	public abstract boolean checkFilter(byte[] key);

	public abstract void force();

	/**
	 * Create an empty, in-memory copy of bloom filter. New updates are written to both filters.
	 * This is written back to disk on #merge()
	 */
	public abstract void fork(int k);

	public abstract void discard();

	public abstract void merge();

	public abstract int getK();

	public abstract boolean needRebuild();

}