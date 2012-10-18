package freenet.support.io;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** An InputStream that always throws EOFException on any read that fails
 * with end of file. */
public class EOFInputStream extends FilterInputStream {

	public EOFInputStream(InputStream in) {
		super(in);
	}
	
	@Override
	public int read() throws IOException {
		int ret = in.read();
		if(ret < 0) throw new EOFException();
		return ret;
	}

	@Override
	public int read(byte[] b) throws IOException {
		int ret = in.read(b);
		if(ret < 0) throw new EOFException();
		return ret;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int ret = in.read(b, off, len);
		if(ret < 0) throw new EOFException();
		return ret;
	}

}
