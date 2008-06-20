/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.security.MessageDigest;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.crypt.SHA256;

/**
 * @author sdiz
 */
public class BloomFilter {
	protected ByteBuffer filter;
	protected final int length;
	/** Number of hash functions */
	protected final int k;
	private ReadWriteLock lock = new ReentrantReadWriteLock();

	/**
	 * Constructor
	 * 
	 * @param length
	 * 		length in bits
	 */
	public BloomFilter(int length, int k) {
		if (length % 8 != 0)
			throw new IllegalArgumentException();

		filter = ByteBuffer.allocate(length / 8);
		this.length = length;
		this.k = k;
	}

	/**
	 * Constructor
	 * 
	 * @param file
	 * 		disk file
	 * @param length
	 * 		length in bits
	 * @throws IOException
	 */
	public BloomFilter(File file, int length, int k) throws IOException {
		if (length % 8 != 0)
			throw new IllegalArgumentException();

		RandomAccessFile raf = new RandomAccessFile(file, "rw");
		raf.setLength(length / 8);
		filter = raf.getChannel().map(MapMode.READ_WRITE, 0, length / 8);

		this.length = length;
		this.k = k;
	}

	public void updateFilter(byte[] key) {
		int[] hashes = getHashes(key);
		lock.writeLock().lock();
		try {
			for (int i = 0; i < k; i++)
				setBit(hashes[i]);
		} finally {
			lock.writeLock().unlock();
		}
	}

	public boolean checkFilter(byte[] key) {
		int[] hashes = getHashes(key);
		lock.readLock().lock();
		try {
			for (int i = 0; i < k; i++)
				if (!getBit(hashes[i]))
					return false;
		} finally {
			lock.readLock().unlock();
		}
		return true;
	}

	private int[] getHashes(byte[] key) {
		int[] hashes = new int[k];

		MessageDigest md = SHA256.getMessageDigest();
		try {
			ByteBuffer bf = null;
			
			for (int i = 0; i < k; i++) {
				if (bf == null || bf.remaining() < 8) {
					md.update(key);
					md.update((byte) i);
					bf = ByteBuffer.wrap(md.digest());
				}

				hashes[i] = (int) ((bf.getLong() & Long.MAX_VALUE) % length);
			}
		} finally {
			SHA256.returnMessageDigest(md);
		}

		return hashes;
	}

	protected boolean getBit(int offset) {
		return (filter.get(offset / 8) & (1 << (offset % 8))) != 0;
	}

	protected void setBit(int offset) {
		byte b = filter.get(offset / 8);
		b |= 1 << (offset % 8);
		filter.put(offset / 8, b);
		
		if (forkedFilter != null) {
			forkedFilter.setBit(offset);
		}
	}

	public void force() {
		if (filter instanceof MappedByteBuffer) {
			((MappedByteBuffer) filter).force();
		}
	}
	
	protected BloomFilter forkedFilter;

	/**
	 * Create an empty, in-memory copy of bloom filter. New updates are written to both filters.
	 * This is written back to disk on #merge()
	 */
	public void fork() {
		lock.writeLock().lock();
		try {
			forkedFilter = new BloomFilter(length, k);
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

	@Override
	protected void finalize() {
		if (filter != null) {
			force();
		}
		filter = null;
	}
}
