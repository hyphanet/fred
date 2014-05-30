/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- non-JDK imports --------------------------------------------------------

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

    public WouldBlockException() {
        super();
    }

    public WouldBlockException(String string) {
        super(string);
    }

    // Optimization :
    // https://blogs.oracle.com/jrose/entry/longjumps_considered_inexpensive
    @Override
    public final synchronized Throwable fillInStackTrace() {
        if (logDEBUG) {
            return super.fillInStackTrace();
        }

        return null;
    }
}
