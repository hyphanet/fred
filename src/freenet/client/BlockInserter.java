package freenet.client;

import freenet.keys.FreenetURI;
import freenet.support.Bucket;

/**
 * Inserts a single splitfile block.
 */
public class BlockInserter extends SplitfileBlock {

	private Bucket data;
	private final int num;
	
	/**
	 * Create a BlockInserter.
	 * @param bucket The data to insert, or null if it will be filled in later.
	 * @param num The block number in the splitfile.
	 */
	public BlockInserter(Bucket bucket, int num) {
		this.data = bucket;
		if(bucket == null) throw new NullPointerException();
		this.num = num;
	}

	int getNumber() {
		return num;
	}

	boolean hasData() {
		return true;
	}

	Bucket getData() {
		return data;
	}

	synchronized void setData(Bucket data) {
		if(this.data != null) throw new IllegalArgumentException("Cannot set data when already have data");
		this.data = data;
	}

	void start() {
		// TODO Auto-generated method stub

	}

	public void kill() {
		// TODO Auto-generated method stub

	}

	public FreenetURI getURI() {
		// TODO Auto-generated method stub
		return null;
	}
}
