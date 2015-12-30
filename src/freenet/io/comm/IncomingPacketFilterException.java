/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import freenet.support.LightweightException;

public class IncomingPacketFilterException extends LightweightException {
	private static final long serialVersionUID = -1;

	public IncomingPacketFilterException(String string) {
		super(string);
	}

	public IncomingPacketFilterException() {
		super();
	}

}
