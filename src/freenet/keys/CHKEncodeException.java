/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

/**
 * @author amphibian
 * 
 * Exception thrown when a CHK encoding fails.
 * Specifically, it is thrown when the data is too big to encode.
 */
public class CHKEncodeException extends KeyEncodeException {
	private static final long serialVersionUID = -1;
    public CHKEncodeException() {
        super();
    }

    public CHKEncodeException(String message) {
        super(message);
    }

    public CHKEncodeException(String message, Throwable cause) {
        super(message, cause);
    }

    public CHKEncodeException(Throwable cause) {
        super(cause);
    }
}
