package freenet.support.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import freenet.support.Logger;

/**
 *
 * @author unknown
 */
public class RandomAccessFileWrapper implements RandomAccessThing {

	// FIXME maybe we should avoid opening these until we are ready to use them
	final RandomAccessFile raf;

	/**
	 *
	 * @param raf
	 */
	public RandomAccessFileWrapper(RandomAccessFile raf) {
		this.raf = raf;
	}

	/**
	 *
	 * @param filename
	 * @param mode
	 * @throws FileNotFoundException
	 */
	public RandomAccessFileWrapper(File filename, String mode) throws FileNotFoundException {
		raf = new RandomAccessFile(filename, mode);
	}

	/**
	 *
	 * @param fileOffset
	 * @param buf
	 * @param bufOffset
	 * @param length
	 * @throws IOException
	 */
	public void pread(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
		synchronized(this) {
			raf.seek(fileOffset);
			raf.readFully(buf, bufOffset, length);
		}
	}

	/**
	 *
	 * @param fileOffset
	 * @param buf
	 * @param bufOffset
	 * @param length
	 * @throws IOException
	 */
	public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
		synchronized(this) {
			raf.seek(fileOffset);
			raf.write(buf, bufOffset, length);
		}
	}

	/**
	 * 
	 * @return
	 * @throws IOException
	 */
	public long size() throws IOException {
		return raf.length();
	}

	public void close() {
		try {
			raf.close();
		} catch (IOException e) {
			Logger.error(this, "Could not close " + raf + " : " + e + " for " + this, e);
		}
	}

}
