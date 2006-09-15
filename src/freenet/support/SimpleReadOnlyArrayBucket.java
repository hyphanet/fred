/*
  SimpleReadOnlyArrayBucket.java / Freenet
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

package freenet.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.support.io.Bucket;

/**
 * Simple read-only array bucket. Just an adapter class to save some RAM.
 * Not the same as ArrayBucket, which can't take a (byte[], offset, len) in
 * constructor (unless we waste some RAM there by using an object to store these
 * instead of storing the byte[]'s directly).
 */
public class SimpleReadOnlyArrayBucket implements Bucket {

	final byte[] buf;
	final int offset;
	final int length;
	
	public SimpleReadOnlyArrayBucket(byte[] buf, int offset, int length) {
		this.buf = buf;
		this.offset = offset;
		this.length = length;
	}
	
	public SimpleReadOnlyArrayBucket(byte[] buf) {
		this(buf, 0, buf.length);
	}
	
	public OutputStream getOutputStream() throws IOException {
		throw new IOException("Read only");
	}

	public InputStream getInputStream() throws IOException {
		return new ByteArrayInputStream(buf, offset, length);
	}

	public String getName() {
		return "SimpleReadOnlyArrayBucket: len="+length+" "+super.toString();
	}

	public long size() {
		return length;
	}

	public boolean isReadOnly() {
		return true;
	}

	public void setReadOnly() {
		// Already read-only
	}

	public void free() {
		// Do nothing
	}

}
