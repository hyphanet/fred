package freenet.crypt;

import java.io.*;

public class DigestOutputStream extends FilterOutputStream {
    protected Digest ctx;

    public DigestOutputStream(Digest d, OutputStream out) {
	super(out);
	this.ctx=d;
    }

    public void write(byte[] b) throws IOException {
	write(b, 0, b.length);
    }

    public void write(byte[] b, int offset, int len) throws IOException {
	ctx.update(b, offset, len);
	out.write(b, offset, len);
    }

    public void write(int b) throws IOException {
	ctx.update((byte)b);
	out.write(b);
    }

    public Digest getDigest() {
	return ctx;
    }
}
	
