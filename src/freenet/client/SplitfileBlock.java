package freenet.client;

import freenet.client.RetryTracker.Level;
import freenet.support.Bucket;

/** Simple interface for a splitfile block */
public abstract class SplitfileBlock {

	/** Get block number. [0,k[ = data blocks, [k, n[ = check blocks */
	abstract int getNumber();
	
	/** Has data? */
	abstract boolean hasData();
	
	/** Get data */
	abstract Bucket getData();
	
	/** Set data */
	abstract void setData(Bucket data);

	private Level level;
	
	final Level getLevel() {
		return level;
	}
	
	final void setLevel(Level l) {
		level = l;
	}
}
