package freenet.config;

import freenet.support.api.ShortCallback;

public class NullShortCallback implements ShortCallback {

	public short get() {
		return 0;
	}

	public void set(short val) throws InvalidConfigValueException {
		// Ignore
	}

}
