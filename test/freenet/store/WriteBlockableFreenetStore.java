package freenet.store;

import java.io.IOException;

public class WriteBlockableFreenetStore<T extends StorableBlock> extends ProxyFreenetStore<T> {

	private boolean blocked;
	private int countBlocked;
	
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
		countBlocked++;
		try {
			notifyAll(); // FIXME use two separate semaphores?
			while(blocked) {
				try {
					wait();
				} catch (InterruptedException e) {
					// Ignore.
				}
			}
		} finally {
			countBlocked--;
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
	
	public synchronized int countBlocked() {
		return countBlocked;
	}
	
	public synchronized void waitForSomeBlocked(int minBlocked) {
		while(countBlocked < minBlocked) {
			try {
				wait();
			} catch (InterruptedException e) {
				// Ignore.
			}
		}
	}
	
	public void waitForSomeBlocked() {
		waitForSomeBlocked(1);
	}

}
