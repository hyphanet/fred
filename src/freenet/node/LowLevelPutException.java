/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.keys.KeyBlock;

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
	
	private KeyBlock collidedBlock;
	
	static String getMessage(int reason) {
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
	
	public LowLevelPutException(int code, String message, Throwable t) {
		super(message, t);
		this.code = code;
	}

	public LowLevelPutException(int reason) {
		super(getMessage(reason));
		this.code = reason;
	}
	
	public LowLevelPutException(KeyBlock collided) {
		super(getMessage(COLLISION));
		this.code = COLLISION;
		collidedBlock = collided;
	}
	
	public synchronized void setCollidedBlock(KeyBlock block) {
		collidedBlock = block;
	}
	
	public synchronized KeyBlock getCollidedBlock() {
		return collidedBlock;
	}
	
}
