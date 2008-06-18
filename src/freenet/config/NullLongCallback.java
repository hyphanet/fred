package freenet.config;

import freenet.support.api.LongCallback;

public class NullLongCallback implements LongCallback {

	public long get() {
		return 0;
	}

	public void set(long val) throws InvalidConfigValueException {
		// Ignore
	}

}
