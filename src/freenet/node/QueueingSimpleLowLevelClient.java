package freenet.node;

import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;

interface QueueingSimpleLowLevelClient extends SimpleLowLevelClient {

	/** Unqueued version. Only call from QueuedDataRequest ! */
	ClientKeyBlock realGetKey(ClientKey key, boolean localOnly, boolean cache, boolean ignoreStore) throws LowLevelGetException;

	/** Ditto */
	void realPut(ClientKeyBlock block, boolean cache) throws LowLevelPutException;

}
