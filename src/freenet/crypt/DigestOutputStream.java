/*
  DigestOutputStream.java / Freenet
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
	
