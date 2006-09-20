/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.io.comm;


/**
 * @author ian
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class RetrievalException extends Exception {
	private static final long serialVersionUID = 3257565105301500723L;

	public static final int UNKNOWN = 0;
	public static final int HTTP_404_RECEIVED = 1;
	public static final int PREMATURE_EOF = 2;
	public static final int IO_ERROR = 3;
	public static final int RANGE_UNSUPPORTED = 4;
	public static final int SENDER_DIED = 5;
	public static final int TIMED_OUT = 4;
    public static final int ALREADY_CACHED = 6;
    public static final int SENDER_DISCONNECTED = 7;
    public static final int NO_DATAINSERT = 8;
	
	int _reason;
	String _cause;

	public RetrievalException(int reason) {
		_reason = reason;
	}
	
	public RetrievalException(int reason, String cause) {
		this(reason);
		_cause = cause;
	}
	
	public int getReason() {
		return _reason;
	}
	
	public String toString() {
		return _cause;
	}
}
