package freenet.io.xfer;

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
