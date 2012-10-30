package freenet.support.io;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class NoCloseProxyOutputStream extends FilterOutputStream {

	public NoCloseProxyOutputStream(OutputStream arg0) {
		super(arg0);
	}
	
	@Override
	public void write(byte[] buf, int offset, int length) throws IOException {
		out.write(buf, offset, length);
	}
	
	@Override
	public void close() throws IOException {
		// Don't close the underlying stream.
		// It probably makes debugging easier to flush it.
		flush();
	}
	
}