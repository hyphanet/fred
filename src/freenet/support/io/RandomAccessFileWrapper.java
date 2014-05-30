/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.io;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.Logger;

//~--- JDK imports ------------------------------------------------------------

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

public class RandomAccessFileWrapper implements RandomAccessThing {
    private boolean closed = false;

    // FIXME maybe we should avoid opening these until we are ready to use them
    final RandomAccessFile raf;

    public RandomAccessFileWrapper(RandomAccessFile raf) {
        this.raf = raf;
    }

    public RandomAccessFileWrapper(File filename, String mode) throws FileNotFoundException {
        raf = new RandomAccessFile(filename, mode);
    }

    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        synchronized (this) {
            raf.seek(fileOffset);
            raf.readFully(buf, bufOffset, length);
        }
    }

    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        synchronized (this) {
            raf.seek(fileOffset);
            raf.write(buf, bufOffset, length);
        }
    }

    @Override
    public long size() throws IOException {
        return raf.length();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;
        }

        try {
            raf.close();
        } catch (IOException e) {
            Logger.error(this, "Could not close " + raf + " : " + e + " for " + this, e);
        }
    }
}
