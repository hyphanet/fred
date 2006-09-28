/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.LowLevelFilterException;

public class PacketSequenceException extends LowLevelFilterException {
	private static final long serialVersionUID = -1;

	public PacketSequenceException(String string) {
		super(string);
	}

}
