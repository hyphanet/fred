/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.crypt;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

public class CryptFormatException extends Exception {
    private static final long serialVersionUID = -796276279268900609L;

    public CryptFormatException(IOException e) {
        super(e.getMessage());
        initCause(e);
    }

    public CryptFormatException(String message) {
        super(message);
    }
}
