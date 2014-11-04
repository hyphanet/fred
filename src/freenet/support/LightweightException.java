/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

/**
 * Exceptions intended be as lightweight as possible, to make them usable for modifying control
 * flow instead of indicating a program error.
 *
 * This kind of exceptions do not provide stack traces. Subclasses can override this behaviour
 * (e.g. for debugging) by overriding {@link #shouldFillInStackTrace()}.
 *
 * @author bertm
 */
public class LightweightException extends Exception {
    private static final long serialVersionUID = -1;

    public LightweightException() {
        super();
    }

    public LightweightException(String message) {
        super(message);
    }

    public LightweightException(Throwable cause) {
        super(cause);
    }

    public LightweightException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Allows subclasses to override the default lack of a stack trace for debugging purposes.
     * The default implementation in {@link LightweightException} always returns {@code false}.
     * @return {@code true} if a stack trace should be provided, {@code false} for lightweight.
     */
    protected boolean shouldFillInStackTrace() {
        return false;
    }

    // Optimization:
    // https://blogs.oracle.com/jrose/entry/longjumps_considered_inexpensive
    @Override
    public final Throwable fillInStackTrace() {
        if (shouldFillInStackTrace())
        {
            return super.fillInStackTrace();
        }
        return null;
    }
}
