package freenet.node;

import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.KeyBlock;

public class QueuedDataRequest extends QueuedRequest {

	private final ClientKey key;
	private final boolean localOnly;
	private final boolean cache;
	private final boolean ignoreStore;
	private QueueingSimpleLowLevelClient client;
	
	public QueuedDataRequest(ClientKey key, boolean localOnly, boolean cache, QueueingSimpleLowLevelClient client, boolean ignoreStore) {
		this.key = key;
		this.localOnly = localOnly;
		this.client = client;
		this.cache = cache;
		this.ignoreStore = ignoreStore;
	}

	public ClientKeyBlock waitAndFetch() throws LowLevelGetException {
		waitForSendClearance();
		return client.realGetKey(key, localOnly, cache, ignoreStore);
	}

}
