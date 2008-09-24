package freenet.config;

import freenet.support.api.BooleanCallback;

public class NullBooleanCallback extends BooleanCallback {

	public Boolean get() {
		return false;
	}

	public void set(Boolean val) throws InvalidConfigValueException {
		// Ignore
	}

}
