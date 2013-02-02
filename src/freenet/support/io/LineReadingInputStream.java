/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import freenet.support.HexUtil;

/**
 * A FilterInputStream which provides readLine().
 */
public class LineReadingInputStream extends FilterInputStream implements LineReader {

	public LineReadingInputStream(InputStream in) {
		super(in);
	}

	/**
	 * Read a \n or \r\n terminated line of UTF-8 or ISO-8859-1.
	 * @param maxLength The maximum length of a line. If a line is longer than this, we throw IOException rather
	 * than keeping on reading it forever.
	 * @param bufferSize The initial size of the read buffer.
	 * @param utf If true, read as UTF-8, if false, read as ISO-8859-1.
	 */
	@Override
	public String readLine(int maxLength, int bufferSize, boolean utf) throws IOException {
		if(maxLength < 1)
			return null;
		if(maxLength <= bufferSize)
			bufferSize = maxLength + 1; // Buffer too big, shrink it (add 1 for the optional \r)

		if(!markSupported())
			return readLineWithoutMarking(maxLength, bufferSize, utf);

		byte[] buf = new byte[Math.max(Math.min(128, maxLength), Math.min(1024, bufferSize))];
		int ctr = 0;
		mark(maxLength + 2); // in case we have both a \r and a \n
		while(true) {
			assert(buf.length - ctr > 0);
			int x = read(buf, ctr, buf.length - ctr);
			if(x < 0) {
				if(ctr == 0)
					return null;
				return new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1");
			}
			if(x == 0) {
				// Don't busy-loop. Probably a socket closed or something.
				// If not, it's not a salavageable situation; either way throw.
				throw new EOFException();
			}
			// REDFLAG this is definitely safe with the above charsets, it may not be safe with some wierd ones.
			int end = ctr + x;
			for(; ctr < end; ctr++) {
				if(buf[ctr] == '\n') {
					String toReturn = "";
					if(ctr != 0) {
						boolean removeCR = (buf[ctr - 1] == '\r');
						toReturn = new String(buf, 0, (removeCR ? ctr - 1 : ctr), utf ? "UTF-8" : "ISO-8859-1");
					}
					reset();
					skip(ctr + 1);
					return toReturn;
				}
				if(ctr >= maxLength)
					throw new TooLongException("We reached maxLength="+maxLength+ " parsing\n "+HexUtil.bytesToHex(buf, 0, ctr) + "\n" + new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1"));
			}
			if((buf.length < maxLength) && (buf.length - ctr < bufferSize)) {
				byte[] newBuf = new byte[Math.min(buf.length * 2, maxLength)];
				System.arraycopy(buf, 0, newBuf, 0, ctr);
				buf = newBuf;
			}
		}
	}

	protected String readLineWithoutMarking(int maxLength, int bufferSize, boolean utf) throws IOException {
		if(maxLength < bufferSize)
			bufferSize = maxLength + 1; // Buffer too big, shrink it (add 1 for the optional \r)
		byte[] buf = new byte[Math.max(Math.min(128, maxLength), Math.min(1024, bufferSize))];
		int ctr = 0;
		while(true) {
			int x = read();
			if(x == -1) {
				if(ctr == 0)
					return null;
				return new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1");
			}
			// REDFLAG this is definitely safe with the above charsets, it may not be safe with some wierd ones.
			if(x == '\n') {
				if(ctr == 0)
					return "";
				if(buf[ctr - 1] == '\r')
					ctr--;
				return new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1");
			}
			if(ctr >= maxLength)
					throw new TooLongException("We reached maxLength="+maxLength+ " parsing\n "+HexUtil.bytesToHex(buf, 0, ctr) + "\n" + new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1"));
			if(ctr >= buf.length) {
				buf = Arrays.copyOf(buf, Math.min(buf.length * 2, maxLength));
			}
			buf[ctr++] = (byte) x;
		}
	}
}
