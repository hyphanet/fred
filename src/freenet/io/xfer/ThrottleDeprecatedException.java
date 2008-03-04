package freenet.io.xfer;

/**
 * Thrown when a throttle is deprecated.
 * @author toad
 */
public class ThrottleDeprecatedException extends Exception {

	ThrottleDeprecatedException(PacketThrottle target) {
		this.target = target;
	}
	
	public final PacketThrottle target;

}
