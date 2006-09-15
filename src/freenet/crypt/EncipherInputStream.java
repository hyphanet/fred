/*
  EncipherInputStream.java / Freenet, Java Adaptive Network Client
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

