package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;

public interface SplitfileBlock {

	/** Get block number. [0,k[ = data blocks, [k, n[ = check blocks */
	abstract int getNumber();
	
	/** Has data? */
	abstract boolean hasData();
	
	/** Get data */
	abstract Bucket getData();
	
	/** Set data */
	abstract void setData(Bucket data);


}
