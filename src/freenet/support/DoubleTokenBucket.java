package freenet.support;

/**
 * A TokenBucket where forceGrab() may only use up to some proportion of the total limit. Beyond that,
 * we ignore it. So the last X% may be used by blocking grabs, even if the forceGrab() traffic is over
 * the limit. This is implemented by using a secondary TokenBucket to track what is allowed.
 */
public class DoubleTokenBucket extends TokenBucket {
	
	private static boolean logMINOR;
	private final TokenBucket grabbedBytesLimiter;
	private final double forceGrabLimit;
	
	public DoubleTokenBucket(long max, long nanosPerTick, long initialValue, double forceGrabLimit) {
		super(max, nanosPerTick, initialValue);
		if(forceGrabLimit > 1.0) throw new IllegalArgumentException();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		grabbedBytesLimiter = new TokenBucket((long)(max * forceGrabLimit), (long)(nanosPerTick / forceGrabLimit), (long)(initialValue * forceGrabLimit));
		this.forceGrabLimit = forceGrabLimit;
	}
	
	@Override
	public synchronized void forceGrab(long tokens) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		long maxTokens = grabbedBytesLimiter.partialInstantGrab(tokens);
		if(maxTokens < tokens) {
			if(logMINOR) Logger.minor(this, "Limiting forceGrab of "+tokens+" to "+maxTokens);
		}
		if(maxTokens > 0)
			super.forceGrab(maxTokens);
	}

	/**
	 * Change the number of nanos per tick.
	 * @param nanosPerTick The new number of nanos per tick.
	 */
	@Override
	public synchronized void changeNanosPerTick(long nanosPerTick) {
		super.changeNanosPerTick(nanosPerTick);
		grabbedBytesLimiter.changeNanosPerTick((long)(nanosPerTick * forceGrabLimit));
	}
	
	@Override
	public synchronized void changeBucketSize(long newMax) {
		super.changeBucketSize(newMax);
		grabbedBytesLimiter.changeBucketSize((long)(newMax * forceGrabLimit));
	}
	
	@Override
	public synchronized void changeNanosAndBucketSize(long nanosPerTick, long newMax) {
		super.changeNanosAndBucketSize(nanosPerTick, newMax);
		grabbedBytesLimiter.changeNanosAndBucketSize((long)(nanosPerTick * forceGrabLimit),
				(long)(newMax * forceGrabLimit));
	}
	
}
