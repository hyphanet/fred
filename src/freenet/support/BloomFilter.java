package freenet.support;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.Random;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.spaceroots.mantissa.random.MersenneTwister;

public abstract class BloomFilter {
	protected ByteBuffer filter;

	/** Number of hash functions */
	protected final int k;
	protected final int length;

	protected ReadWriteLock lock = new ReentrantReadWriteLock();

	protected BloomFilter(int length, int k) {
		if (length % 8 != 0)
			throw new IllegalArgumentException();

		this.length = length;
		this.k = k;
	}

	//-- Core
	public void addKey(byte[] key) {
		Random hashes = getHashes(key);
		lock.writeLock().lock();
		try {
			for (int i = 0; i < k; i++)
				setBit(hashes.nextInt(length));
		} finally {
			lock.writeLock().unlock();
		}

		if (forkedFilter != null)
			forkedFilter.addKey(key);
	}

	public boolean checkFilter(byte[] key) {
		Random hashes = getHashes(key);
		lock.readLock().lock();
		try {
			for (int i = 0; i < k; i++)
				if (!getBit(hashes.nextInt(length)))
					return false;
		} finally {
			lock.readLock().unlock();
		}
		return true;
	}

	public void removeKey(byte[] key) {
		Random hashes = getHashes(key);
		lock.writeLock().lock();
		try {
			for (int i = 0; i < k; i++)
				unsetBit(hashes.nextInt(length));
		} finally {
			lock.writeLock().unlock();
		}

		if (forkedFilter != null)
			forkedFilter.addKey(key);
	}

	//-- Bits and Hashes
	protected abstract boolean getBit(int offset);

	protected abstract void setBit(int offset);

	protected abstract void unsetBit(int offset);

	protected Random getHashes(byte[] key) {
		return new MersenneTwister(key);
	}

	//-- Fork & Merge
	protected BloomFilter forkedFilter;

	/**
	 * Create an empty, in-memory copy of bloom filter. New updates are written to both filters.
	 * This is written back to disk on #merge()
	 */
	public abstract void fork(int k);

	public void merge() {
		lock.writeLock().lock();
		try {
			if (forkedFilter == null)
				return;

			filter.position(0);
			forkedFilter.filter.position(0);

			filter.put(forkedFilter.filter);

			filter.position(0);
			forkedFilter = null;
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void discard() {
		lock.writeLock().lock();
		try {
			forkedFilter = null;
		} finally {
			lock.writeLock().unlock();
		}
	}

	//-- Misc.
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

	public int getK() {
		return k;
	}

	protected boolean needRebuild;

	public boolean needRebuild() {
		boolean _needRebuild = needRebuild;
		needRebuild = false;
		return _needRebuild;

	}

	public void force() {
		if (filter instanceof MappedByteBuffer) {
			((MappedByteBuffer) filter).force();
		}
	}

	@Override
	protected void finalize() {
		if (filter != null) {
			force();
		}
		filter = null;
		forkedFilter = null;
	}
}
