package freenet.support.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import freenet.support.Logger;

public class RandomAccessFileWrapper implements RandomAccessThing {

	// FIXME maybe we should avoid opening these until we are ready to use them
	final RandomAccessFile raf;
	private boolean closed = false;
	
	public RandomAccessFileWrapper(RandomAccessFile raf) {
		this.raf = raf;
	}
	
	public RandomAccessFileWrapper(File filename, String mode) throws FileNotFoundException {
		raf = new RandomAccessFile(filename, mode);
	}

	@Override
	public void pread(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
		synchronized(this) {
			raf.seek(fileOffset);
			raf.readFully(buf, bufOffset, length);
		}
	}

	@Override
	public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length)
			throws IOException {
		synchronized(this) {
			raf.seek(fileOffset);
			raf.write(buf, bufOffset, length);
		}
	}

	@Override
	public long size() throws IOException {
		return raf.length();
	}

	@Override
	public void close() {
		synchronized(this) {
			if(closed) return;
			closed = true;
		}
		try {
			raf.close();
		} catch (IOException e) {
			Logger.error(this, "Could not close "+raf+" : "+e+" for "+this, e);
		}
	}

}
