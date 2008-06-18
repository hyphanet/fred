package freenet.config;

import freenet.support.api.BooleanCallback;

public class NullBooleanCallback implements BooleanCallback {

	public boolean get() {
		return false;
	}

	public void set(boolean val) throws InvalidConfigValueException {
		// Ignore
	}

}
