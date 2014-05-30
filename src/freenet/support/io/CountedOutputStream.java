/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.io;

//~--- JDK imports ------------------------------------------------------------

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class CountedOutputStream extends FilterOutputStream {
    private long written;

    public CountedOutputStream(OutputStream arg0) {
        super(arg0);
    }

    @Override
    public void write(int x) throws IOException {
        super.write(x);
        written++;
    }

    @Override
    public void write(byte[] buf) throws IOException {
        write(buf, 0, buf.length);
    }

    @Override
    public void write(byte[] buf, int offset, int length) throws IOException {
        out.write(buf, offset, length);
        written += length;
    }

    public long written() {
        return written;
    }
}
