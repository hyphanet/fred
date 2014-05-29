/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import freenet.keys.KeyVerifyException;

public class BinaryBlobFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    public BinaryBlobFormatException(String message) {
        super(message);
    }

    public BinaryBlobFormatException(String message, KeyVerifyException e) {
        super(message, e);
    }
}
