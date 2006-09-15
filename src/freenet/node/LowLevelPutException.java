/*
  LowLevelPutException.java / Freenet
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

package freenet.node;

public class LowLevelPutException extends Exception {
	private static final long serialVersionUID = 1L;
	/** An internal error occurred */
	public static final int INTERNAL_ERROR = 1;
	/** The request could not go enough hops to store the data properly. */
	public static final int ROUTE_NOT_FOUND = 2;
	/** A downstream node is overloaded, and rejected the insert. We should
	 * reduce our rate of sending inserts. */
	public static final int REJECTED_OVERLOAD = 3;
	/** Insert could not get off the node at all */
	public static final int ROUTE_REALLY_NOT_FOUND = 4;
	/** Insert collided with pre-existing, different content. Can only happen with KSKs and SSKs. */
	public static final int COLLISION = 5;
	
	/** Failure code */
	public final int code;
	
	static final String getMessage(int reason) {
		switch(reason) {
		case INTERNAL_ERROR:
			return "Internal error - probably a bug";
		case ROUTE_NOT_FOUND:
			return "Could not store the data on enough nodes";
		case REJECTED_OVERLOAD:
			return "A node downstream either timed out or was overloaded (retry)";
		case ROUTE_REALLY_NOT_FOUND:
			return "The insert could not get off the node at all";
		case COLLISION:
			return "The insert collided with different data of the same key already on the network";
		default:
			return "Unknown error code: "+reason;
		}
		
	}
	
	LowLevelPutException(int code, String message, Throwable t) {
		super(message, t);
		this.code = code;
	}

	LowLevelPutException(int reason) {
		super(getMessage(reason));
		this.code = reason;
	}
	
}
