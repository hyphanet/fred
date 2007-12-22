package freenet.io.comm;

public class NullAsyncMessageFilterCallback implements
		AsyncMessageFilterCallback {

	public void onMatched(Message m) {
		// Do nothing
	}

	public boolean shouldTimeout() {
		// Not until matched.
		return false;
	}

	public void onTimeout() {
		// Do nothing
	}

}
