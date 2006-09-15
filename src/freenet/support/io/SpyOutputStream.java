/*
  SpyOUtputStream.java / Freenet
  Copyright (C) ian
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

package freenet.support.io;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import freenet.support.Logger;

/**
 * @author ian
 * @see freenet.support.SpyInputStream
 */
public class SpyOutputStream extends FilterOutputStream {

	private String prefix = "";
	private TempFileBucket tfb = null;

	private void debugPrintLn(String text) {
		if (Logger.shouldLog(Logger.DEBUG,this))
			Logger.debug(this, text);
	}

	private final void checkValid() throws IOException {
		synchronized (tfb) {
			if (tfb.isReleased()) {
				throw new IOException(
					"Attempt to use a released TempFileBucket: " + prefix);
			}
		}
	}

	/**
	 * Constructor for the SpyOutputStream object
	 * 
	 * @param tfb
	 * @param pref
	 * @exception IOException
	 */
	public SpyOutputStream(TempFileBucket tfb, String pref)
		throws IOException {
		super(null);
		OutputStream tmpOut = null;
		try {
			this.prefix = pref;
			this.tfb = tfb;
			checkValid();
			tmpOut = tfb.getRealOutputStream();
			out = tmpOut;
		} catch (IOException ioe) {
			//ioe.printStackTrace();
			debugPrintLn("SpyOutputStream ctr failed!: " + ioe.toString());
			try {
				if (tmpOut != null) {
					tmpOut.close();
				}
			} catch (Exception e0) {
				// NOP
			}
			debugPrintLn("SpyOutputStream ctr failed!: " + ioe.toString());
			throw ioe;
		}
		debugPrintLn("Created new OutputStream");
	}

	////////////////////////////////////////////////////////////
	// FilterOutputStream implementation

	public void write(int b) throws IOException {
		synchronized (tfb) {
			//       println(".write(b)");
			checkValid();
			out.write(b);
		}
	}

	public void write(byte[] buf) throws IOException {
		synchronized (tfb) {
			//       println(".write(buf)");
			checkValid();
			out.write(buf);
		}
	}

	public void write(byte[] buf, int off, int len) throws IOException {
		synchronized (tfb) {
			//       println(".write(buf,off,len)");
			checkValid();
			out.write(buf, off, len);
		}
	}

	public void flush() throws IOException {
		synchronized (tfb) {
			debugPrintLn(".flush()");
			checkValid();
			out.flush();
		}
	}

	public void close() throws IOException {
		synchronized (tfb) {
			debugPrintLn(".close()");
			checkValid();
			out.close();
			if (tfb.streams.contains(out)) {
				tfb.streams.removeElement(out);
			}
		}
	}
}
