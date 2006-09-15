/*
  DigestInputStream.java / Freenet
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
	
