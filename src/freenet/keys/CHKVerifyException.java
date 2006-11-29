/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

/**
 * @author amphibian
 * 
 * Exception thrown when a CHK doesn't verify.
 */
public class CHKVerifyException extends KeyVerifyException {
	private static final long serialVersionUID = -1;

    public CHKVerifyException() {
        super();
    }

    public CHKVerifyException(String message) {
        super(message);
    }

    public CHKVerifyException(String message, Throwable cause) {
        super(message, cause);
    }

    public CHKVerifyException(Throwable cause) {
        super(cause);
    }

}
