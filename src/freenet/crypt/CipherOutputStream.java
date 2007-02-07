/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

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
        this(PCFBMode.create(c), out);
    }

    public CipherOutputStream(BlockCipher c, OutputStream out, byte[] iv) {
        this(PCFBMode.create(c, iv), out);
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
    
    // FOS will use write(int) to implement this if we don't override it!
    public void write(byte[] buf) throws IOException {
    	write(buf, 0, buf.length);
    }
}






