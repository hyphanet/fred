/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.BufferedReader;
import java.io.IOException;

/**
** Utility class for all sorts of Readers.
*/
final public class Readers {

	private Readers() { }

	/**
	** A {@link LineReader} <a href="http://en.wikipedia.org/wiki/Adapter_pattern">Adapter</a>
	** for {@link BufferedReader}.
	*/
	public static LineReader fromBufferedReader(final BufferedReader br) {
		return new LineReader() {
			@Override
			public String readLine(int maxLength, int bufferSize, boolean utf) throws IOException {
				return br.readLine();
			}
		};
	}
	
	/**
	 *  A {@link LineReader} <a href="http://en.wikipedia.org/wiki/Adapter_pattern">Adapter</a>
	 * for {@link String} array.
	 */
	public static LineReader fromStringArray(final String[] lines) {
		return new LineReader() {
			private int currentLine = -1;
			@Override
			public String readLine(int maxLength, int bufferSize, boolean utf) throws IOException {
				if(++currentLine<lines.length) {
					return lines[currentLine];
				} 
				return null;
			}
		};
	}

}
