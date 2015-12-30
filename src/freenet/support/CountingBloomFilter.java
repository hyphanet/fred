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
public class CountingBloomFilter extends BloomFilter {
	
	private boolean warnOnRemoveFromEmpty;
	
	public void setWarnOnRemoveFromEmpty() {
		warnOnRemoveFromEmpty = true;
	}
	
	/**
	 * Constructor
	 * 
	 * @param length
	 *            length in bits
	 */
	public CountingBloomFilter(int length, int k) {
		super(length, k);
		filter = ByteBuffer.allocate(this.length / 4);
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
	protected CountingBloomFilter(File file, int length, int k) throws IOException {
		super(length, k);
		int fileLength = length / 4;
		if (!file.exists() || file.length() != fileLength)
			needRebuild = true;

		RandomAccessFile raf = new RandomAccessFile(file, "rw");
		FileChannel channel = null;
		try {
			raf.setLength(fileLength);
			channel = raf.getChannel();
			filter = channel.map(MapMode.READ_WRITE, 0, fileLength).load();
		} finally {
			Closer.close(raf);
			Closer.close(channel);
		}
	}

	public CountingBloomFilter(int length, int k, byte[] buffer) {
		super(length, k);
		assert(buffer.length == length / 4);
		filter = ByteBuffer.wrap(buffer);
	}

	@Override
	public boolean getBit(int offset) {
		byte b = filter.get(offset / 4);
		byte v = (byte) ((b >>> offset % 4 * 2) & 3);

		return v != 0;
	}

	@Override
	public void setBit(int offset) {
		byte b = filter.get(offset / 4);
		byte v = (byte) ((b >>> offset % 4 * 2) & 3);

		if (v == 3)
			return; // overflow

		b &= ~(3 << offset % 4 * 2); // unset bit
		b |= (v + 1) << offset % 4 * 2; // set bit

		filter.put(offset / 4, b);
	}

	@Override
	public void unsetBit(int offset) {
		byte b = filter.get(offset / 4);
		byte v = (byte) ((b >>> offset % 4 * 2) & 3);

		if (v == 0 && warnOnRemoveFromEmpty)
			Logger.error(this, "Unsetting bit but already unset - probable double remove, can cause false negatives, is very bad!", new Exception("error"));
		
		if (v == 0 || v == 3)
			return; // overflow / underflow

		b &= ~(3 << offset % 4 * 2); // unset bit
		b |= (v - 1) << offset % 4 * 2; // set bit

		filter.put(offset / 4, b);
	}

	@Override
	public void fork(int k) {
		lock.writeLock().lock();
		try {
			File tempFile = File.createTempFile("bloom-", ".tmp");
			tempFile.deleteOnExit();
			forkedFilter = new CountingBloomFilter(tempFile, length, k);
		} catch (IOException e) {
			forkedFilter = new CountingBloomFilter(length, k);
		} finally {
			lock.writeLock().unlock();
		}
	}

}
