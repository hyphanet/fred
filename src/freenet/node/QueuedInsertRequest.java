package freenet.node;

import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKeyBlock;

public class QueuedInsertRequest extends QueuedRequest {

	private final ClientKeyBlock block;
	private final boolean cache;
	private QueueingSimpleLowLevelClient client;
	
	public QueuedInsertRequest(ClientKeyBlock block, QueueingSimpleLowLevelClient client, boolean cache) {
		this.block = block;
		this.client = client;
		this.cache = cache;
	}

	public void waitAndPut() throws LowLevelPutException {
		waitForSendClearance();
		client.realPut(block, cache);
	}
}
