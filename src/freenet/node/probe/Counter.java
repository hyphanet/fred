package freenet.node.probe;

class Counter {
	/**
	 * Maximum number of accepted probes in the past minute.
	 */
	public static final byte MAX_ACCEPTED = 10;

	private byte c = 0;

	public void increment() {
		c++;
		if (c > MAX_ACCEPTED) {
			/*
			 * The counter should never be incremented above the maximum, as an increment should
			 * only happen after it has been confirmed to be below the limit. If this happens, it
			 * indicates a concurrency problem or logic error.
			 */
			throw new IllegalStateException("Number of accepted probes exceeds the maximum: " + c);
		}
	}

	public void decrement() {
		c--;
		if (c < 0) {
			/*
			 * The counter should never be decremented lower than zero, as a decrement should always
			 * be paired with an increment before it, and if a counter reaches zero it should be
			 * removed to avoid memory leaks. If this happens, it indicates a concurrency problem or
			 * logic error.
			 */
			throw new IllegalStateException("Number of accepted probes is negative: " + c);
		}
	}

	public byte value() {
		return c;
	}
}
