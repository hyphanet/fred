package freenet.crypt;
/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

import java.io.*;

/**
 * Implements a Java InputStream that encrypts the substream on the way in.
 * This differs from CipherInputStream, which is decrypting an already
 * encrypted source
 */
public class EncipherInputStream extends FilterInputStream {

    protected PCFBMode ctx;

    public EncipherInputStream(InputStream in, BlockCipher c) {
        this(in, new PCFBMode(c));
    }

    public EncipherInputStream(InputStream in, BlockCipher c, int bufSize) {
        this(bufSize == 0 ? in : new BufferedInputStream(in, bufSize), c);
    }

    public EncipherInputStream(InputStream in, PCFBMode ctx) {
        super(in);
        this.ctx = ctx;
    }

    public EncipherInputStream(InputStream in, PCFBMode ctx, int bufSize) {
        this(bufSize == 0 ? in : new BufferedInputStream(in, bufSize), ctx);
    }

    public int read() throws IOException {
        int rv=in.read();
        return (rv==-1 ? -1 : ctx.encipher(rv));
    }

    public int read(byte[] b, int off, int len) throws IOException {
        int rv=in.read(b, off, len);
        if (rv != -1) ctx.blockEncipher(b, off, rv);
        return rv;
    }
}

