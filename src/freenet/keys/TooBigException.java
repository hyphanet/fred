/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.keys;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

public class TooBigException extends IOException {
    private static final long serialVersionUID = 1L;

    public TooBigException(String msg) {
        super(msg);
    }
}
