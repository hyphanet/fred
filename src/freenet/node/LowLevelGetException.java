/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class LowLevelGetException extends Exception {
    private static volatile boolean logDEBUG;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {

            @Override
            public void shouldUpdate() {
                logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
            }
        });
    }

	private static final long serialVersionUID = 1L;
	/** Decode of data failed, probably was bogus at source */
	public static final int DECODE_FAILED = 1;
	/** Data was not in store and request was local-only */
	public static final int DATA_NOT_FOUND_IN_STORE = 2;
	/** An internal error occurred */
	public static final int INTERNAL_ERROR = 3;
	/** The request went to many hops, but could not find the data. Maybe
	 * it doesn't exist. */
	public static final int DATA_NOT_FOUND = 4;
	/** The request could not find enough nodes to visit while looking for
	 * the datum. */
	public static final int ROUTE_NOT_FOUND = 5;
	/** A downstream node is overloaded, and rejected the request. We should
	 * reduce our rate of sending requests.
	 */
	public static final int REJECTED_OVERLOAD = 6;
	/** Transfer of data started, but then failed. */
	public static final int TRANSFER_FAILED = 7;
	/** Data successfully transferred, but was not valid (at the node key level
	 * i.e. before decode) */
	public static final int VERIFY_FAILED = 8;
	/** Request cancelled by user */
	public static final int CANCELLED = 9;
	/** Ran into a failure table */
	public static final int RECENTLY_FAILED = 10;
	
	public static String getMessage(int reason) {
		switch(reason) {
		case DECODE_FAILED:
			return "Decode of data failed, probably was bogus at source";
		case DATA_NOT_FOUND_IN_STORE:
			return "Data was not in store and request was local-only";
		case INTERNAL_ERROR:
			return "Internal error - probably a bug";
		case DATA_NOT_FOUND:
			return "Could not find the data";
		case ROUTE_NOT_FOUND:
			return "Could not find enough nodes to be sure that the data is not out there somewhere";
		case REJECTED_OVERLOAD:
			return "A node downstream either timed out or was overloaded (retry)";
		case TRANSFER_FAILED:
			return "Started to transfer data, then failed (should be rare)";
		case VERIFY_FAILED:
			return "Node sent us invalid data";
		case CANCELLED:
			return "Request cancelled";
		case RECENTLY_FAILED:
			return "Request killed by failure table due to recently DNFing on a downstream node";
		default:
			return "Unknown error code: "+reason;
		}
	}
	
	/** Failure code */
	public final int code;
	
	public LowLevelGetException(int code, String message, Throwable t) {
		super(message, t);
		this.code = code;
	}

	public LowLevelGetException(int code, String message) {
		super(message);
		this.code = code;
	}

	public LowLevelGetException(int reason) {
		super(getMessage(reason));
		this.code = reason;
	}
	
	@Override
	public String toString() {
		return super.toString()+':'+getMessage(code);
	}

    @Override
    public final synchronized Throwable fillInStackTrace() {
        if(logDEBUG || code == INTERNAL_ERROR || code == DECODE_FAILED || code == VERIFY_FAILED)
            return super.fillInStackTrace();
        return null;
    }

}
