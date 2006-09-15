/*
  DecipherOutputStream.java / Freenet
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



