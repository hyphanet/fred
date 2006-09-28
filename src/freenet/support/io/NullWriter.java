/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.IOException;
import java.io.Writer;

public class NullWriter extends Writer {

	public void write(char[] cbuf, int off, int len) throws IOException {
	}

	public void flush() throws IOException {
	}

	public void close() throws IOException {
	}

}
