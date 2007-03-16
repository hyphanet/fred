/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.client.async.ClientRequester;
import freenet.keys.KeyBlock;
import freenet.support.Logger;

/**
 * Simple SendableInsert implementation. No feedback, no retries, just insert the
 * block. Not designed for use by the client layer. Used by the node layer for the
 * 1 in every 200 successful requests which starts an insert.
 */
public class SimpleSendableInsert extends SendableInsert {

	public final NodeClientCore node;
	public final KeyBlock block;
	public final short prioClass;
	private boolean finished;
	
	public SimpleSendableInsert(NodeClientCore node, KeyBlock block, short prioClass) {
		this.node = node;
		this.block = block;
		this.prioClass = prioClass;
	}
	
	public void onSuccess() {
		// Yay!
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Finished insert of "+block);
	}

	public void onFailure(LowLevelPutException e) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Failed insert of "+block+": "+e);
	}

	public short getPriorityClass() {
		return prioClass;
	}

	public int getRetryCount() {
		// No retries.
		return 0;
	}

	public boolean send(NodeClientCore core) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		try {
			if(logMINOR) Logger.minor(this, "Starting request: "+this);
			core.realPut(block, false);
		} catch (LowLevelPutException e) {
			onFailure(e);
			if(logMINOR) Logger.minor(this, "Request failed: "+this+" for "+e);
			return true;
		} finally {
			finished = true;
		}
		if(logMINOR) Logger.minor(this, "Request succeeded: "+this);
		onSuccess();
		return true;
	}

	public Object getClient() {
		return node;
	}

	public ClientRequester getClientRequest() {
		return null;
	}

	public boolean isCancelled() {
		return finished;
	}

	public boolean canRemove() {
		return true;
	}

}
