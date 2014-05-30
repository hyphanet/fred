/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.keys;

public class KeyVerifyException extends Exception {
    private static final long serialVersionUID = -1;

    public KeyVerifyException() {
        super();
    }

    public KeyVerifyException(String message) {
        super(message);
    }

    public KeyVerifyException(Throwable cause) {
        super(cause);
    }

    public KeyVerifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
