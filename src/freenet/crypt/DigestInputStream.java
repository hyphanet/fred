/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.*;

public class DigestInputStream extends FilterInputStream {
    protected Digest ctx;

    public DigestInputStream(Digest d, InputStream in) {
	super(in);
	this.ctx=d;
    }

    public int read(byte[] b, int offset, int len) throws IOException {
	int rl=super.read(b, offset, len);
	if (rl>0) ctx.update(b, offset, rl);
	return rl;
    }

    public int read() throws IOException {
	int rv=super.read();
	if (rv!=-1) ctx.update((byte)rv);
	return rv;
    }

    public Digest getDigest() {
	return ctx;
    }
}
	
