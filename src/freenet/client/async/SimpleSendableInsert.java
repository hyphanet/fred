package freenet.client.async;

import freenet.keys.ClientKeyBlock;
import freenet.keys.KeyBlock;
import freenet.node.LowLevelPutException;
import freenet.node.Node;
import freenet.support.Logger;

/**
 * Simple SendableInsert implementation. No feedback, no retries, just insert the
 * block. Not designed for use by the client layer. Used by the node layer for the
 * 1 in every 200 successful requests which starts an insert.
 */
public class SimpleSendableInsert implements SendableInsert {

	public final Node node;
	public final KeyBlock block;
	public final short prioClass;
	private boolean finished;
	
	public SimpleSendableInsert(Node node, KeyBlock block, short prioClass) {
		this.node = node;
		this.block = block;
		this.prioClass = prioClass;
	}
	
	public void onSuccess() {
		// Yay!
		Logger.minor(this, "Finished insert of "+block);
	}

	public void onFailure(LowLevelPutException e) {
		Logger.minor(this, "Failed insert of "+block+": "+e);
	}

	public short getPriorityClass() {
		return prioClass;
	}

	public int getRetryCount() {
		// No retries.
		return 0;
	}

	public void send(Node node) {
		try {
			Logger.minor(this, "Starting request: "+this);
			node.realPut(block, false);
		} catch (LowLevelPutException e) {
			onFailure(e);
			Logger.minor(this, "Request failed: "+this+" for "+e);
			return;
		} finally {
			finished = true;
		}
		Logger.minor(this, "Request succeeded: "+this);
		onSuccess();
	}

	public Object getClient() {
		return node;
	}

	public ClientRequester getClientRequest() {
		return null;
	}

	public boolean isFinished() {
		return finished;
	}

}
