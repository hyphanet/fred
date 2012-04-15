/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.message.xfer;

/**
 * Thrown when a throttle is deprecated.
 * @author toad
 */
public class ThrottleDeprecatedException extends Exception {

	private static final long serialVersionUID = -4542976419025644806L;

	ThrottleDeprecatedException(PacketThrottle target) {
		this.target = target;
	}
	
	public final PacketThrottle target;

}
