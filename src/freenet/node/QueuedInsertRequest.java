package freenet.node;

import freenet.keys.ClientCHKBlock;

public class QueuedInsertRequest extends QueuedRequest {

	private final ClientCHKBlock block;
	private final boolean cache;
	private QueueingSimpleLowLevelClient client;
	
	public QueuedInsertRequest(ClientCHKBlock block, QueueingSimpleLowLevelClient client, boolean cache) {
		this.block = block;
		this.client = client;
		this.cache = cache;
	}

	public void waitAndPut() throws LowLevelPutException {
		waitForSendClearance();
		client.realPutCHK(block, cache);
	}
}
