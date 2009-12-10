/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import freenet.support.HexUtil;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

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
	public String readLine(int maxLength, int bufferSize, boolean utf) throws IOException {
		if(maxLength < 1) {
			return null;
		}
		if(maxLength <= bufferSize) {
			bufferSize = maxLength + 1; // Buffer too big, shrink it (add 1 for the optional \r)
		}
		if(!markSupported()) {
			return readLineWithoutMarking(maxLength, bufferSize, utf);
		}

		byte[] buf = new byte[Math.max(Math.min(128, maxLength), Math.min(1024, bufferSize))];
		int ctr = 0;
		mark(maxLength + 2); // in case we have both a \r and a \n
		while (true) {
			int x = read(buf, ctr, buf.length - ctr);
			if(x == -1) {
				if(ctr == 0) {
					return null;
				}
				return new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1");
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
				if(ctr >= maxLength) {
					throw new TooLongException("We reached maxLength=" + maxLength + " parsing\n " + HexUtil.bytesToHex(buf, 0, ctr) + "\n" + new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1"));
				}
			}
			if((buf.length < maxLength) && (buf.length - ctr < bufferSize)) {
				byte[] newBuf = new byte[Math.min(buf.length * 2, maxLength)];
				System.arraycopy(buf, 0, newBuf, 0, ctr);
				buf = newBuf;
			}
		}
	}

	public String readLineWithoutMarking(int maxLength, int bufferSize, boolean utf) throws IOException {
		if(maxLength < bufferSize) {
			bufferSize = maxLength + 1; // Buffer too big, shrink it (add 1 for the optional \r)
		}
		byte[] buf = new byte[Math.max(Math.min(128, maxLength), Math.min(1024, bufferSize))];
		int ctr = 0;
		while (true) {
			int x = read();
			if(x == -1) {
				if(ctr == 0) {
					return null;
				}
				return new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1");
			}
			// REDFLAG this is definitely safe with the above charsets, it may not be safe with some wierd ones. 
			if(x == '\n') {
				if(ctr == 0) {
					return "";
				}
				if(buf[ctr - 1] == '\r') {
					ctr--;
				}
				return new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1");
			}
			if(ctr >= maxLength) {
				throw new TooLongException("We reached maxLength=" + maxLength + " parsing\n " + HexUtil.bytesToHex(buf, 0, ctr) + "\n" + new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1"));
			}
			if(ctr >= buf.length) {
				byte[] newBuf = new byte[Math.min(buf.length * 2, maxLength)];
				System.arraycopy(buf, 0, newBuf, 0, buf.length);
				buf = newBuf;
			}
			buf[ctr++] = (byte) x;
		}
	}

}
