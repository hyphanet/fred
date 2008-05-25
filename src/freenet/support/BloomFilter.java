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

import freenet.crypt.SHA256;

/**
 * @author sdiz
 */
//TODO use ReadWriteLock once we move to java 5
public class BloomFilter {
	protected ByteBuffer filter;
	protected final int length;
	/** Number of hash functions */
	protected final int k;

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

	public synchronized void updateFilter(byte[] key) {
		int[] hashes = getHashes(key);
		for (int i = 0; i < k; i++)
			setBit(hashes[i]);
		force();
	}

	public synchronized boolean checkFilter(byte[] key) {
		int[] hashes = getHashes(key);
		for (int i = 0; i < k; i++)
			if (!getBit(hashes[i]))
				return false;
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
		return (filter.get(offset) & (1 << (offset % 8))) != 0;
	}

	protected void setBit(int offset) {
		byte b = filter.get(offset / 8);
		b |= 1 << (offset % 8);
		filter.put(offset / 8, b);
	}

	private void force() {
		if (filter instanceof MappedByteBuffer) {
			((MappedByteBuffer) filter).force();
		}
	}

	protected void finalize() {
		if (filter != null) {
			force();
		}
		filter = null;
	}
}
