package freenet.crypt;
/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/
import java.io.*;

/**
 * Implements a Java OutputStream that is encrypted with any symmetric block
 * cipher (implementing the BlockCipher interface).
 * 
 * This stream operates in Periodic Cipher Feedback Mode (PCFB), allowing 
 * byte at a time encryption with no additional encryption workload.
 */
public class CipherOutputStream extends FilterOutputStream {

    private final PCFBMode ctx;
    
    public PCFBMode getCipher() {
	return ctx;
    }
    
    public CipherOutputStream(BlockCipher c, OutputStream out) {
        this(new PCFBMode(c), out);
    }

    public CipherOutputStream(BlockCipher c, OutputStream out, byte[] iv) {
        this(new PCFBMode(c, iv), out);
    }

    public CipherOutputStream(PCFBMode ctx, OutputStream out) {
        super(out);
        this.ctx = ctx;
    }

    //int wrote = 0;            
    public void write(int b) throws IOException {
        //System.err.println("WRITING BYTE: " + wrote++);
        out.write(ctx.encipher(b));
    }

    public void write(byte[] buf, int off, int len) throws IOException {
        //System.err.println("WRITING BUF LENGTH : " + (wrote += len));
        byte[] tmp = new byte[len];
        System.arraycopy(buf, off, tmp, 0, len);
        ctx.blockEncipher(tmp, 0, len);
        out.write(tmp);
    }
}






