package freenet.client;

import freenet.support.Bucket;
import freenet.support.Logger;

public abstract class StdSplitfileBlock extends SplitfileBlock implements Runnable {

	Bucket fetchedData;
	protected final RetryTracker tracker;
	/** Splitfile index - [0,k[ is the data blocks, [k,n[ is the check blocks */
	protected final int index;

	public StdSplitfileBlock(RetryTracker tracker2, int index2, Bucket data) {
		if(tracker2 == null) throw new NullPointerException();
		this.tracker = tracker2;
		this.index = index2;
		this.fetchedData = data;
	}

	public int getNumber() {
		return index;
	}

	public boolean hasData() {
		return fetchedData != null;
	}

	public Bucket getData() {
		return fetchedData;
	}

	public void setData(Bucket data) {
		fetchedData = data;
		Logger.minor(this, "Set data: "+(data == null ? "(null)" : (""+data.size())+ " on "+this), new Exception("debug"));
	}

	public void start() {
		checkStartable();
		Logger.minor(this, "Starting "+this);
		try {
			Thread t = new Thread(this);
			t.setDaemon(true);
			t.start();
		} catch (Throwable error) {
			tracker.fatalError(this, InserterException.INTERNAL_ERROR);
			Logger.error(this, "Caught "+error+" creating thread for "+this);
		}
	}

	protected abstract void checkStartable();
}
