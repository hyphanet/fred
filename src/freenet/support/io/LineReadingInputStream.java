package freenet.support.io;

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

	private byte[] buf;

	/**
	 * Read a \n or \r\n terminated line of UTF-8 or ISO-8859-1.
	 */
	public String readLine(int maxLength, int bufferSize, boolean utf) throws IOException {
		if(buf == null)
			buf = new byte[Math.max(128, Math.min(1024, bufferSize))];
		int ctr = 0;
		while(true) {
			int x = read();
			if(x == -1) {
				if(ctr == 0) return null;
				return new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1");
			}
			// REDFLAG this is definitely safe with the above charsets, it may not be safe with some wierd ones. 
			if(x == (int)'\n') {
				if(ctr == 0) return "";
				if(buf[ctr-1] == '\r') ctr--;
				return new String(buf, 0, ctr, utf ? "UTF-8" : "ISO-8859-1");
			}
			if(ctr >= buf.length) {
				if(buf.length == maxLength) throw new TooLongException();
				byte[] newBuf = new byte[Math.min(buf.length * 2, maxLength)];
				System.arraycopy(buf, 0, newBuf, 0, buf.length);
				buf = newBuf;
			}
			buf[ctr++] = (byte)x;
		}
	}
	
}
