package freenet.support.io;

import java.io.EOFException;
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
	 * Read a line of US-ASCII. Used for e.g. HTTP. @return Null if end of file.
	 */
	public String readLine(int maxLength, int bufferSize) throws IOException {
		StringBuffer sb = new StringBuffer(bufferSize);
		while(true) {
			int x = read();
			if(x == -1) {
				if(sb.length() == 0) return null;
				return sb.toString();
			}
			char c = (char) x;
			if(c == '\n') {
				if(sb.length() > 0) {
					if(sb.charAt(sb.length()-1) == '\r')
						sb.setLength(sb.length()-1);
				}
				return sb.toString();
			}
			sb.append(c);
			if(sb.length() >= maxLength)
				throw new TooLongException();
		}
	}
	
}
