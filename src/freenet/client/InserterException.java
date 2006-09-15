/*
  InserterException.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Logger;

public class InserterException extends Exception {
	private static final long serialVersionUID = -1106716067841151962L;
	
	private final int mode;
	/** For collection errors */
	public FailureCodeTracker errorCodes;
	/** If a non-serious error, the URI */
	public FreenetURI uri;
	
	public final String extra;
	
	/** Get the failure mode. */
	public int getMode() {
		return mode;
	}
	
	public InserterException(int m, String msg, FreenetURI expectedURI) {
		super(getMessage(m)+": "+msg);
		extra = msg;
		mode = m;
		if(Logger.shouldLog(Logger.MINOR, getClass()))
			Logger.minor(this, "Creating InserterException: "+getMessage(mode)+": "+msg, this);
		errorCodes = null;
		this.uri = expectedURI;
	}
	
	public InserterException(int m, FreenetURI expectedURI) {
		super(getMessage(m));
		extra = null;
		mode = m;
		if(Logger.shouldLog(Logger.MINOR, getClass()))
			Logger.minor(this, "Creating InserterException: "+getMessage(mode), this);
		errorCodes = null;
		this.uri = expectedURI;
	}

	public InserterException(int mode, Throwable e, FreenetURI expectedURI) {
		super(getMessage(mode)+": "+e.getMessage());
		extra = e.getMessage();
		if(Logger.shouldLog(Logger.MINOR, getClass()))
			Logger.minor(this, "Creating InserterException: "+getMessage(mode)+": "+e, e);
		this.mode = mode;
		errorCodes = null;
		initCause(e);
		this.uri = expectedURI;
	}

	public InserterException(int mode, FailureCodeTracker errorCodes, FreenetURI expectedURI) {
		super(getMessage(mode));
		extra = null;
		this.mode = mode;
		if(Logger.shouldLog(Logger.MINOR, getClass()))
			Logger.minor(this, "Creating InserterException: "+getMessage(mode), this);
		this.errorCodes = errorCodes;
		this.uri = expectedURI;
	}

	public InserterException(int mode) {
		super(getMessage(mode));
		extra = null;
		this.mode = mode;
		this.errorCodes = null;
		this.uri = null;
	}

	/** Caller supplied a URI we cannot use */
	public static final int INVALID_URI = 1;
	/** Failed to read from or write to a bucket; a kind of internal error */
	public static final int BUCKET_ERROR = 2;
	/** Internal error of some sort */
	public static final int INTERNAL_ERROR = 3;
	/** Downstream node was overloaded */
	public static final int REJECTED_OVERLOAD = 4;
	/** Couldn't find enough nodes to send the data to */
	public static final int ROUTE_NOT_FOUND = 5;
	/** There were fatal errors in a splitfile insert. */
	public static final int FATAL_ERRORS_IN_BLOCKS = 6;
	/** Could not insert a splitfile because a block failed too many times */
	public static final int TOO_MANY_RETRIES_IN_BLOCKS = 7;
	/** Not able to leave the node at all */
	public static final int ROUTE_REALLY_NOT_FOUND = 8;
	/** Collided with pre-existing content */
	public static final int COLLISION = 9;
	/** Cancelled by user */
	public static final int CANCELLED = 10;
	
	public static String getMessage(int mode) {
		switch(mode) {
		case INVALID_URI:
			return "Caller supplied a URI we cannot use";
		case BUCKET_ERROR:
			return "Internal bucket error: out of disk space/permissions problem?";
		case INTERNAL_ERROR:
			return "Internal error";
		case REJECTED_OVERLOAD:
			return "A downstream node timed out or was severely overloaded";
		case FATAL_ERRORS_IN_BLOCKS:
			return "Fatal errors in a splitfile insert";
		case TOO_MANY_RETRIES_IN_BLOCKS:
			return "Could not insert splitfile: ran out of retries (nonfatal errors)";
		case ROUTE_NOT_FOUND:
			return "Could not propagate the insert to enough nodes (normal on small networks, try fetching it anyway)";
		case ROUTE_REALLY_NOT_FOUND:
			return "Insert could not leave the node at all";
		case COLLISION:
			return "Insert collided with different, pre-existing data at the same key";
		case CANCELLED:
			return "Cancelled by user";
		default:
			return "Unknown error "+mode;
		}
	}

	public static String getShortMessage(int mode) {
		switch(mode) {
		case INVALID_URI:
			return "Invalid URI";
		case BUCKET_ERROR:
			return "Temp files error";
		case INTERNAL_ERROR:
			return "Internal error";
		case REJECTED_OVERLOAD:
			return "Timeout or overload";
		case FATAL_ERRORS_IN_BLOCKS:
			return "Some blocks failed fatally";
		case TOO_MANY_RETRIES_IN_BLOCKS:
			return "Some blocks ran out of retries";
		case ROUTE_NOT_FOUND:
			return "Route not found";
		case ROUTE_REALLY_NOT_FOUND:
			return "Request could not leave the node";
		case COLLISION:
			return "Collided with existing data";
		case CANCELLED:
			return "Cancelled";
		default:
			return "Unknown error "+mode;
		}
	}
	
	/** Is this error fatal? Non-fatal errors are errors which are likely to go away with
	 * more retries, or at least for which there is some point retrying.
	 */
	public boolean isFatal() {
		return isFatal(mode);
	}
	
	public static boolean isFatal(int mode) {
		switch(mode) {
		case INVALID_URI:
		case FATAL_ERRORS_IN_BLOCKS:
		case COLLISION:
		case CANCELLED:
			return true;
		case BUCKET_ERROR: // maybe
		case INTERNAL_ERROR: // maybe
		case REJECTED_OVERLOAD:
		case TOO_MANY_RETRIES_IN_BLOCKS:
		case ROUTE_NOT_FOUND:
		case ROUTE_REALLY_NOT_FOUND:
			return false;
		default:
			Logger.error(InserterException.class, "Error unknown to isFatal(): "+getMessage(mode));
			return false;
		}
	}

	public static InserterException construct(FailureCodeTracker errors) {
		if(errors == null) return null;
		if(errors.isEmpty()) return null;
		if(errors.isOneCodeOnly()) {
			return new InserterException(errors.getFirstCode());
		}
		int mode;
		if(errors.isFatal(true))
			mode = FATAL_ERRORS_IN_BLOCKS;
		else
			mode = TOO_MANY_RETRIES_IN_BLOCKS;
		return new InserterException(mode, errors, null);
	}
}
