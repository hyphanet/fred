package freenet.support;

import freenet.io.comm.IncomingPacketFilterException;
import freenet.support.Logger.LogLevel;

/**
 * Thrown when we would have to block but have been told not to.
 */
public class WouldBlockException extends IncomingPacketFilterException {

    private static final long serialVersionUID = -1;
    private static volatile boolean logDEBUG;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {

            @Override
            public void shouldUpdate() {
                logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
            }
        });
    }

    public WouldBlockException(String string) {
        super(string);
    }

    public WouldBlockException() {
        super();
    }

    // Optimization :
    // https://blogs.oracle.com/jrose/entry/longjumps_considered_inexpensive
    @Override
    public final synchronized Throwable fillInStackTrace() {
        if(logDEBUG)
            return super.fillInStackTrace();
        return null;
    }
}
