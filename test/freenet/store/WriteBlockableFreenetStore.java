package freenet.store;

import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class WriteBlockableFreenetStore<T extends StorableBlock> extends ProxyFreenetStore<T> {

	private boolean blocked;
	private int countBlocked;
	private final Lock lock = new ReentrantLock();
	private final Condition blockedChanged = lock.newCondition();
	private final Condition countBlockedIncreased = lock.newCondition();
	
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

	void waitForUnblocked() {
		lock.lock();
		try {
			countBlocked++;
			countBlockedIncreased.signalAll();
			while(blocked) {
				blockedChanged.awaitUninterruptibly();
			}
		} finally {
			countBlocked--;
			lock.unlock();
		}
	}
	
	public void setBlocked(boolean blocked) {
		lock.lock();
		try {
			this.blocked = blocked;
			blockedChanged.signalAll();
		} finally {
			lock.unlock();
		}
	}
	
	public void unblock() {
		setBlocked(false);
	}
	
	public void block() {
		setBlocked(true);
	}
	
	public int countBlocked() {
		lock.lock();
		try {
			return countBlocked;
		} finally {
			lock.unlock();
		}
	}
	
	public void waitForSomeBlocked(int minBlocked) {
		lock.lock();
		try {
			while(countBlocked < minBlocked) {
				countBlockedIncreased.awaitUninterruptibly();
			}
		} finally {
			lock.unlock();
		}
	}
	
	public void waitForSomeBlocked() {
		waitForSomeBlocked(1);
	}

}
