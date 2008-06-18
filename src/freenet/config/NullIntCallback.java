package freenet.config;

import freenet.support.api.IntCallback;

public class NullIntCallback implements IntCallback {

	public int get() {
		return 0;
	}

	public void set(int val) throws InvalidConfigValueException {
		// Ignore
	}

}
