package freenet.config;

import freenet.support.api.IntCallback;

public class NullIntCallback extends IntCallback {

	@Override
	public Integer get() {
		return 0;
	}

	@Override
	public void set(Integer val) throws InvalidConfigValueException {
		// Ignore
	}

}
