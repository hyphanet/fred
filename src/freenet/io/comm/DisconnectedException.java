/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import freenet.support.LightweightException;

/**
 * Thrown when the node is disconnected in the middle of (or
 * at the beginning of) a waitFor(). Not the same as 
 * NotConnectedException.
 */
public class DisconnectedException extends LightweightException {
	private static final long serialVersionUID = -1;
}
