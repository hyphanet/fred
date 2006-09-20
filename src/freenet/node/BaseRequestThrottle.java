package freenet.node;

public interface BaseRequestThrottle {

	public static final long DEFAULT_DELAY = 200;
	static final long MAX_DELAY = 5*60*1000;
	static final long MIN_DELAY = 20;

	/**
	 * Get the current inter-request delay.
	 */
	public abstract long getDelay();

}