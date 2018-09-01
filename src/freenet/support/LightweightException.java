/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

/**
 * These exceptions are intended to be as lightweight as possible so that they may be used for
 * normal control flow instead of indicating a program error.
 *
 * This kind of exception does not provide stack traces. Subclasses can override this behaviour
 * (e.g. for debugging) by overriding {@link #shouldFillInStackTrace()}.
 *
 * @see <a href="https://blogs.oracle.com/jrose/entry/longjumps_considered_inexpensive">
 *     Optimization: Longjumps Considered Inexpensive</a>
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

    @Override
    public final Throwable fillInStackTrace() {
        if (shouldFillInStackTrace()) {
            return super.fillInStackTrace();
        }
        return null;
    }
}
