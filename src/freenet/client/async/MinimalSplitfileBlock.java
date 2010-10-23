package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.SplitfileBlock;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public class MinimalSplitfileBlock implements SplitfileBlock {

	// LOCKING: MinimalSplitfileBlock is accessed by the client database thread and by the FEC threads.
	// Therefore we need synchronization on data.

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public final int number;
	private Bucket data;
	boolean flag;
	
	public MinimalSplitfileBlock(int n) {
		this.number = n;
	}

	public int getNumber() {
		return number;
	}

	public boolean hasData() {
		return data != null;
	}

	public synchronized Bucket getData() {
		return data;
	}

	/** Set the data but only if there is no data already. 
	 * @return True if we set the data to the new bucket. */
	public synchronized Bucket trySetData(Bucket data) {
		if(this.data != null) return this.data;
		this.data = data;
		return null;
	}

	/** Set the data, assert that it is null before being set */
	public synchronized void assertSetData(Bucket data) {
		assert(this.data == null || this.data == data);
		this.data = data;
	}
	
	public synchronized Bucket clearData() {
		Bucket ret = data;
		data = null;
		return ret;
	}

	/** Replace the data - set it and return the old data */
	public synchronized Bucket replaceData(Bucket data) {
		Bucket ret = this.data;
		this.data = data;
		return ret;
	}

	// Useful for debugging duplicate object bugs. But use the new logging infrastructure if you reinstate it, please.
//	public void objectOnDeactivate(ObjectContainer container) {
//		if(Logger.shouldLog(LogLevel.MINOR, this))
//			Logger.minor(this, "Deactivating "+this, new Exception("debug"));
//	}
//
	public synchronized void storeTo(ObjectContainer container) {
		if(data != null)
			data.storeTo(container);
		container.store(this);
		if(logMINOR)
			Logger.minor(this, "Storing "+this+" with data: "+data+" id = "+container.ext().getID(this));
	}

	public synchronized void removeFrom(ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "Removing "+this+" with data: "+data);
		if(data != null) {
			container.activate(data, 1);
			data.removeFrom(container);
		}
		container.delete(this);
	}

}
