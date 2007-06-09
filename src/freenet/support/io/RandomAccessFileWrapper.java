package freenet.support.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import freenet.support.Logger;

public class RandomAccessFileWrapper implements RandomAccessThing {

	// FIXME maybe we should avoid opening these until we are ready to use them
	final RandomAccessFile raf;
	
	public RandomAccessFileWrapper(RandomAccessFile raf) {
		this.raf = raf;
	}
	
	public RandomAccessFileWrapper(File filename, String mode) throws FileNotFoundException {
		raf = new RandomAccessFile(filename, mode);
	}

	public void pread(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
		synchronized(this) {
			raf.seek(fileOffset);
			raf.readFully(buf, bufOffset, length);
		}
	}

	public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
		synchronized(this) {
			raf.seek(fileOffset);
			raf.write(buf, bufOffset, length);
		}
	}

	public long size() throws IOException {
		return raf.length();
	}

	public void close() {
		try {
			raf.close();
		} catch (IOException e) {
			Logger.error(this, "Could not close "+raf+" : "+e+" for "+this, e);
		}
	}

}
