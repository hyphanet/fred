package freenet.config;

import freenet.support.api.ShortCallback;

public class NullShortCallback extends ShortCallback {

	@Override
	public Short get() {
		return 0;
	}

	@Override
	public void set(Short val) throws InvalidConfigValueException {
		// Ignore
	}

}
