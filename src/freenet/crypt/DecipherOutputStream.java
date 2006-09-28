/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.*;

/**
 * Implements a Java OutputStream that decrypts the data before writing
 * to the substream.
 * This differs from CipherOutputStream, which encrypts the data instead.
 * @author tavin
 */
public class DecipherOutputStream extends FilterOutputStream {
    
    private PCFBMode ctx;

    public DecipherOutputStream(OutputStream out, BlockCipher c) {
        this(out, new PCFBMode(c));
    }

    public DecipherOutputStream(OutputStream out, BlockCipher c, int bufSize) {
        this(new BufferedOutputStream(out, bufSize), c);
    }

    public DecipherOutputStream(OutputStream out, PCFBMode ctx) {
        super(out);
        this.ctx = ctx;
    }

    public DecipherOutputStream(OutputStream out, PCFBMode ctx, int bufSize) {
        this(new BufferedOutputStream(out, bufSize), ctx);
    }

    public void write(int b) throws IOException {
        out.write(ctx.decipher(b));
    }
    
    public void write(byte[] buf, int off, int len) throws IOException {
        byte[] tmp = new byte[len];
        System.arraycopy(buf, off, tmp, 0, len);
        ctx.blockDecipher(tmp, 0, len);
        out.write(tmp);
    }
}



