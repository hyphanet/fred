package freenet.config;

import freenet.support.api.LongCallback;

public class NullLongCallback extends LongCallback {

	@Override
	public Long get() {
		return 0L;
	}

	@Override
	public void set(Long val) throws InvalidConfigValueException {
		// Ignore
	}

}
