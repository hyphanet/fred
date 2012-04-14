/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.node.transport.PacketTracker;

/**
 * Thrown when a packet hasn't been acked despite 10 minutes of asking for
 * an ack. This results in the connection being closed and the packet
 * which was being sent being killed. We have to throw to avoid locking
 * issues.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class StillNotAckedException extends Exception {
	private static final long serialVersionUID = 1L;

	public StillNotAckedException(PacketTracker tracker) {
		this.tracker = tracker;
	}
	
	final PacketTracker tracker;
	
}
