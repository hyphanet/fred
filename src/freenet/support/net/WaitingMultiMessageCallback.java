package freenet.support.net;

public class WaitingMultiMessageCallback extends MultiMessageCallback {

	
	
	@Override
	protected synchronized void finish(boolean success) {
		notifyAll();
	}

	public synchronized void waitFor() {
		while(!finished()) {
			try {
				wait();
			} catch (InterruptedException e) {
				// Ignore
			}
		}
	}

	@Override
	protected void sent(boolean success) {
		// Ignore
	}

}
