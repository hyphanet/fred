package freenet.node;

import freenet.keys.ClientKey;
import freenet.keys.KeyBlock;

public class QueuedDataRequest extends QueuedRequest {

	private final ClientKey key;
	private final boolean localOnly;
	private QueueingSimpleLowLevelClient client;
	
	public QueuedDataRequest(ClientKey key, boolean localOnly, QueueingSimpleLowLevelClient client) {
		this.key = key;
		this.localOnly = localOnly;
		this.client = client;
	}

	public KeyBlock waitAndFetch() throws LowLevelGetException {
		waitForSendClearance();
		return client.realGetKey(key, localOnly);
	}

}
