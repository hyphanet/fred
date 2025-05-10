package freenet.crypt;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class MultiHashOutputStream extends FilterOutputStream {

	private final MultiHashDigester digester;
	
	public MultiHashOutputStream(OutputStream proxy, long generateHashes) {
		super(proxy);
		this.digester = MultiHashDigester.fromBitmask(generateHashes);
	}
	
	@Override
	public void write(int b) throws IOException {
		out.write(b);
		digester.update(new byte[] { (byte) b }, 0, 1);
	}
	
	@Override
	public void write(byte[] buf, int off, int len) throws IOException {
		out.write(buf, off, len);
		digester.update(buf, off, len);
	}
	
	public HashResult[] getResults() {
		return digester.getResults();
	}
}
