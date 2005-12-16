package freenet.node;

import freenet.client.InsertBlock;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.KeyBlock;

interface QueueingSimpleLowLevelClient extends SimpleLowLevelClient {

	/** Unqueued version. Only call from QueuedDataRequest ! */
	ClientKeyBlock realGetKey(ClientKey key, boolean localOnly, boolean cache) throws LowLevelGetException;

	/** Ditto */
	void realPutCHK(ClientCHKBlock block, boolean cache) throws LowLevelPutException;

}
