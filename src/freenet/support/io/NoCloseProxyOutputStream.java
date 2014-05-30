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

public class NoCloseProxyOutputStream extends FilterOutputStream {
    public NoCloseProxyOutputStream(OutputStream arg0) {
        super(arg0);
    }

    @Override
    public void write(byte[] buf, int offset, int length) throws IOException {
        out.write(buf, offset, length);
    }

    @Override
    public void close() throws IOException {

        // Don't close the underlying stream.
        // It probably makes debugging easier to flush it.
        flush();
    }
}
