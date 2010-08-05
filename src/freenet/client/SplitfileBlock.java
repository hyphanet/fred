package freenet.client;

import com.db4o.ObjectContainer;

import freenet.support.api.Bucket;

public interface SplitfileBlock {

	/** Get block number. [0,k[ = data blocks, [k, n[ = check blocks */
	abstract int getNumber();
	
	/** Has data? */
	abstract boolean hasData();
	
	/** Get data */
	abstract Bucket getData();
	
	abstract void storeTo(ObjectContainer container);

	/** Set the data but only if there is no data already. 
	 * @return True if we set the data to the new bucket. */
	abstract Bucket trySetData(Bucket data);

	/** Set the data, assert that it is null before being set */
	abstract void assertSetData(Bucket data);
	
	abstract Bucket clearData();

	/** Replace the data - set it and return the old data */
	abstract Bucket replaceData(Bucket data);

}
