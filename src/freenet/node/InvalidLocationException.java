/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

/**
 * A checked exception that is thrown when a {@link Location} is encountered in a place where a
 * valid location was expected.
 *
 * This exception is intended for control flow (i.e. signalling an invalid location) and omits
 * filling in a stack trace when it is thrown for performance reasons.
 *
 * @see ValidLocation
 * @see Location#validated()
 *
 * @author bertm
 */
public class InvalidLocationException extends Exception {
    private static final long serialVersionUID = 0L;

    /**
     * Constructs the exception with the given detail message.
     * @param message the message
     */
    public InvalidLocationException(String message) {
        super(message);
    }
    
    /**
     * Constructs the exception with the given detail message and cause.
     * @param message the message
     * @param cause the cause
     */
    public InvalidLocationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Do not provide a stack trace: this class is intended to influence control flow instead of
     * indicating a program error.
     * @return {@code null}
     */
    @Override
    public final Throwable fillInStackTrace() {
        return null;
    }
}

