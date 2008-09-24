package freenet.config;

import freenet.support.api.IntCallback;

public class NullIntCallback extends IntCallback {

	public Integer get() {
		return 0;
	}

	public void set(Integer val) throws InvalidConfigValueException {
		// Ignore
	}

}
