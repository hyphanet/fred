/*
  CipherOutputStream.java / Freenet, Java Adaptive Network Client
  Copyright (C) Ian Clarke
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
    
    // FOS will use write(int) to implement this if we don't override it!
    public void write(byte[] buf) throws IOException {
    	write(buf, 0, buf.length);
    }
}






