package freenet.node;

/**
 * RequestThrottle which takes a second throttle, and never
 * returns a delay less than that throttle's current delay. 
 */
public class ChainedRequestThrottle extends RequestThrottle {

	private final RequestThrottle otherThrottle;
	
	public ChainedRequestThrottle(int rtt, float winsz, RequestThrottle other) {
		super(rtt, winsz);
		otherThrottle = other;
	}
	
	public long getDelay() {
		long delay = super.getDelay();
		long otherDelay = otherThrottle.getDelay();
		return Math.max(delay, otherDelay);
	}
	
}
