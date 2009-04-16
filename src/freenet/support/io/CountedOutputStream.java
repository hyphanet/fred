package freenet.support.io;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class CountedOutputStream extends FilterOutputStream {

	private long written;
	
	public CountedOutputStream(OutputStream arg0) {
		super(arg0);
	}
	
	@Override
	public void write(int x) throws IOException {
		super.write(x);
		written++;
	}
	
	@Override
	public void write(byte[] buf) throws IOException {
		write(buf, 0, buf.length);
	}
	
	@Override
	public void write(byte[] buf, int offset, int length) throws IOException {
		out.write(buf, offset, length);
		written += length;
	}

	public long written() {
		return written;
	}

}
