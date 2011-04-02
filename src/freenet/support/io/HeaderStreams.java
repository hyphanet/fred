/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;

import java.util.Arrays;

/**
** Utilities for manipulating headers on streams.
**
** @author infinity0
*/
final public class HeaderStreams {

	private HeaderStreams() {}

	/**
	** Create an {@InputStream} which transparently attaches an extra header
	** to the underlying stream.
	*/
	public static InputStream augInput(final byte[] hd, InputStream s) throws IOException {
		return new FilterInputStream(s) {
			private int i = 0;

			@Override public int available() throws IOException {
				return (hd.length-i) + in.available();
			}

			@Override public int read() throws IOException {
				return (i < hd.length)? ((int)hd[i++])&0xff: in.read();
			}

			@Override public int read(byte[] buf, int off, int len) throws IOException {
				int w = i;
				for (; i<hd.length && len>0; i++, off++, len--) {
					buf[off] = hd[i];
				}
				return (w-i) + in.read(buf, off, len);
				//System.out.println("" + System.identityHashCode(this) + " || augInput read: " + Arrays.toString(buf) + " off " + off + " len " + len);
			}

			@Override public long skip(long n) throws IOException {
				int left = hd.length-i;
				return left + in.skip(n-left);
			}

			@Override public boolean markSupported() {
				// TODO LOW
				return false;
			}

			@Override public void mark(int limit) {
				// TODO LOW
			}

			@Override public void reset() throws IOException {
				// TODO LOW
				throw new IOException("mark/reset not supported");
			}


		};
	}

	/**
	** Create an {@OutputStream} which transparently swallows the expected
	** header written to the underlying stream.
	**
	** The {@code write} methods will throw {@link IOException} if bytes
	** different from the header are written.
	*/
	public static OutputStream dimOutput(final byte[] hd, OutputStream s) {
		return new FilterOutputStream(s) {
			private int i = 0;

			@Override public void write(int b) throws IOException {
				if (i < hd.length) {
					if ((byte)b != hd[i]) { throw new IOException("byte " + i + ": expected '" + hd[i] + "'; got '" + b + "'."); }
					i++;
				} else {
					out.write(b);
				}
			}

			@Override public void write(byte[] buf, int off, int len) throws IOException {
				for (; i<hd.length && len>0; i++, off++, len--) {
					if (buf[off] != hd[i]) { throw new IOException("byte " + i + ": expected '" + hd[i] + "'; got '" + buf[off] + "'."); }
				}
				out.write(buf, off, len);
			}

		};
	}

}
