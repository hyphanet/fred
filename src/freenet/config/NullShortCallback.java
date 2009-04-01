package freenet.config;

import freenet.support.api.ShortCallback;

public class NullShortCallback extends ShortCallback {

	public Short get() {
		return 0;
	}

	public void set(Short val) throws InvalidConfigValueException {
		// Ignore
	}

}
