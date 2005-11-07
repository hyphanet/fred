package freenet.client;

import freenet.node.SimpleLowLevelClient;
import freenet.support.BucketFactory;

/** Context object for an insert operation, including both simple and multi-file inserts */
public class InserterContext {

	final SimpleLowLevelClient client;
	final BucketFactory bf;
	/** If true, don't try to compress the data */
	final boolean dontCompress;
	
	public InserterContext(SimpleLowLevelClient client, BucketFactory bf) {
		this.client = client;
		this.bf = bf;
		dontCompress = false;
	}

}
