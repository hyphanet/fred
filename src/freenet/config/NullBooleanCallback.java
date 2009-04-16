package freenet.config;

import freenet.support.api.BooleanCallback;

public class NullBooleanCallback extends BooleanCallback {

	@Override
	public Boolean get() {
		return false;
	}

	@Override
	public void set(Boolean val) throws InvalidConfigValueException {
		// Ignore
	}

}
