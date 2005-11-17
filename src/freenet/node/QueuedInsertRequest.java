package freenet.node;

import freenet.keys.ClientCHKBlock;

public class QueuedInsertRequest extends QueuedRequest {

	private final ClientCHKBlock block;
	private QueueingSimpleLowLevelClient client;
	
	public QueuedInsertRequest(ClientCHKBlock block, QueueingSimpleLowLevelClient client) {
		this.block = block;
		this.client = client;
	}

	public void waitAndPut() throws LowLevelPutException {
		waitForSendClearance();
		client.realPutCHK(block);
	}
}
