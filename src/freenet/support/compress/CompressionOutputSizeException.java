/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.compress;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

/**
 * The output was too big for the buffer.
 */
public class CompressionOutputSizeException extends IOException {
    private static final long serialVersionUID = -1;
    public final long estimatedSize;

    CompressionOutputSizeException() {
        this(-1);
    }

    CompressionOutputSizeException(long sz) {
        super("The output was too big for the buffer; estimated size: " + sz);
        estimatedSize = sz;
    }
}
