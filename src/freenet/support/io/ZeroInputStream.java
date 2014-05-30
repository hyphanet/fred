/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.io;

//~--- JDK imports ------------------------------------------------------------

import java.io.InputStream;

public class ZeroInputStream extends InputStream {
    @Override
    public int read() {
        return 0;
    }

    @Override
    public int read(byte[] buf) {
        return read(buf, 0, buf.length);
    }

    @Override
    public int read(byte[] buf, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            buf[i] = 0;
        }

        return length;
    }
}
