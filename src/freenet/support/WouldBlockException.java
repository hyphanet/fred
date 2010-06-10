package freenet.support;

import freenet.io.comm.IncomingPacketFilterException;
import freenet.support.Logger.LoggerPriority;

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
                logDEBUG = Logger.shouldLog(LoggerPriority.DEBUG, this);
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
    // http://blogs.sun.com/jrose/entry/longjumps_considered_inexpensive?resubmit=damnit
    @Override
    public final synchronized Throwable fillInStackTrace() {
        if(logDEBUG)
            return super.fillInStackTrace();
        return null;
    }
}
