/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.compress;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

public class InvalidCompressedDataException extends IOException {
    private static final long serialVersionUID = -1L;

    public InvalidCompressedDataException() {
        super();
    }

    public InvalidCompressedDataException(String msg) {
        super(msg);
    }
}
