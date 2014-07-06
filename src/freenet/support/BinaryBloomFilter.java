/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import freenet.support.io.Closer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;

/**
 * @author sdiz
 */
public class BinaryBloomFilter extends BloomFilter {
	/**
	 * Constructor
	 * 
	 * @param length
	 *            length in bits
	 */
	protected BinaryBloomFilter(int length, int k) {
		super(length, k);
		filter = ByteBuffer.allocate(this.length / 8);
	}

	/**
	 * Constructor
	 * 
	 * @param file
	 *            disk file
	 * @param length
	 *            length in bits
	 * @throws IOException
	 */
	protected BinaryBloomFilter(File file, int length, int k) throws IOException {
		super(length, k);
		if (!file.exists() || file.length() != length / 8)
			needRebuild = true;

		RandomAccessFile raf = new RandomAccessFile(file, "rw");
		FileChannel channel = null;
		try {
			raf.setLength(length / 8);
			channel = raf.getChannel();
			filter = channel.map(MapMode.READ_WRITE, 0, length / 8).load();
		} finally {
			Closer.close(raf);
			Closer.close(channel);
		}
	}

	public BinaryBloomFilter(ByteBuffer slice, int length, int k) {
		super(length, k);
		filter = slice;
	}

	@Override
	public void removeKey(byte[] key) {
		// ignore
	}

	@Override
	protected boolean getBit(int offset) {
		return (filter.get(offset / 8) & (1 << (offset % 8))) != 0;
	}

	@Override
	protected void setBit(int offset) {
		byte b = filter.get(offset / 8);
		b |= 1 << (offset % 8);
		filter.put(offset / 8, b);
	}

	@Override
	protected void unsetBit(int offset) {
		// NO-OP
	}

	@Override
	public void fork(int k) {
		lock.writeLock().lock();
		try {
			File tempFile = File.createTempFile("bloom-", ".tmp");
			tempFile.deleteOnExit();
			forkedFilter = new BinaryBloomFilter(tempFile, length, k);
		} catch (IOException e) {
			forkedFilter = new BinaryBloomFilter(length, k);
		} finally {
			lock.writeLock().unlock();
		}
	}

}
