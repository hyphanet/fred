package freenet.client;

import freenet.support.Bucket;

/** Simple interface for a splitfile block */
public interface SplitfileBlock {

	/** Get block number. [0,k[ = data blocks, [k, n[ = check blocks */
	int getNumber();
	
	/** Has data? */
	boolean hasData();
	
	/** Get data */
	Bucket getData();
	
	/** Set data */
	void setData(Bucket data);
	
}
