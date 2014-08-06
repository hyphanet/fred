package freenet.client.async;

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

	@Override
	public int getNumber() {
		return number;
	}

	@Override
	public boolean hasData() {
		return data != null;
	}

	@Override
	public synchronized Bucket getData() {
		return data;
	}

	/** Set the data but only if there is no data already. 
	 * @return True if we set the data to the new bucket. */
	@Override
	public synchronized Bucket trySetData(Bucket data) {
		if(this.data != null) return this.data;
		this.data = data;
		return null;
	}

	/** Set the data, assert that it is null before being set */
	@Override
	public synchronized void assertSetData(Bucket data) {
		assert(this.data == null || this.data == data);
		this.data = data;
	}
	
	@Override
	public synchronized Bucket clearData() {
		Bucket ret = data;
		data = null;
		return ret;
	}

	/** Replace the data - set it and return the old data */
	@Override
	public synchronized Bucket replaceData(Bucket data) {
		Bucket ret = this.data;
		this.data = data;
		return ret;
	}

}
