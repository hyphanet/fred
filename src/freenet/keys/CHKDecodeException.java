/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

/**
 * @author amphibian
 * 
 * Exception thrown when decode fails.
 */
public class CHKDecodeException extends KeyDecodeException {
    private static final long serialVersionUID = -1;
    
    public CHKDecodeException() {
        super();
    }

    public CHKDecodeException(String message) {
        super(message);
    }

    public CHKDecodeException(String message, Throwable cause) {
        super(message, cause);
    }

    public CHKDecodeException(Throwable cause) {
        super(cause);
    }

}
