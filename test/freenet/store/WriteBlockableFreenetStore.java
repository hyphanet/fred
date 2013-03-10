package freenet.store;

import java.io.IOException;

public class WriteBlockableFreenetStore<T extends StorableBlock> extends ProxyFreenetStore<T> {

	private boolean blocked;
	
	public WriteBlockableFreenetStore(FreenetStore<T> backDatastore, boolean initialValue) {
		super(backDatastore);
		blocked = initialValue;
	}
	
	@Override
	public void put(T block, byte[] data, byte[] header, boolean overwrite,
			boolean oldBlock) throws IOException, KeyCollisionException {
		waitForUnblocked();
		super.put(block, data, header, overwrite, oldBlock);
	}

	synchronized void waitForUnblocked() {
		while(blocked) {
			try {
				wait();
			} catch (InterruptedException e) {
				// Ignore.
			}
		}
	}
	
	public synchronized void setBlocked(boolean blocked) {
		this.blocked = blocked;
		notifyAll();
	}
	
	public void unblock() {
		setBlocked(false);
	}
	
	public void block() {
		setBlocked(true);
	}

}
