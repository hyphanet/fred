package freenet.config;

import freenet.support.api.LongCallback;

public class NullLongCallback extends LongCallback {

	public Long get() {
		return 0L;
	}

	public void set(Long val) throws InvalidConfigValueException {
		// Ignore
	}

}
