/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.InputStream;

public class MockInputStream extends InputStream {

	public MockInputStream() {
	}

	@Override
	public int read() {
		return -1;
	}

	@Override
	public int read(byte[] data, int offset, int len) {
		return len;
	}
}