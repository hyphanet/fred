/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.async.ChosenRequest;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequester;
import freenet.keys.CHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.KeyBlock;
import freenet.keys.SSKBlock;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * Simple SendableInsert implementation. No feedback, no retries, just insert the
 * block. Not designed for use by the client layer. Used by the node layer for the
 * 1 in every 200 successful requests which starts an insert.
 */
public class SimpleSendableInsert extends SendableInsert {

	public final KeyBlock block;
	public final short prioClass;
	private boolean finished;
	public final RequestClient client;
	public final ClientRequestScheduler scheduler;
	
	public SimpleSendableInsert(NodeClientCore core, KeyBlock block, short prioClass) {
		super(false);
		this.block = block;
		this.prioClass = prioClass;
		this.client = core.node.nonPersistentClient;
		if(block instanceof CHKBlock)
			scheduler = core.requestStarters.chkPutScheduler;
		else if(block instanceof SSKBlock)
			scheduler = core.requestStarters.sskPutScheduler;
		else
			throw new IllegalArgumentException("Don't know what to do with "+block);
		if(!scheduler.isInsertScheduler())
			throw new IllegalStateException("Scheduler "+scheduler+" is not an insert scheduler!");
	}
	
	public SimpleSendableInsert(KeyBlock block, short prioClass, RequestClient client, ClientRequestScheduler scheduler) {
		super(false);
		this.block = block;
		this.prioClass = prioClass;
		this.client = client;
		this.scheduler = scheduler;
	}
	
	public void onSuccess(Object keyNum, ObjectContainer container, ClientContext context) {
		// Yay!
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Finished insert of "+block);
	}

	public void onFailure(LowLevelPutException e, Object keyNum, ObjectContainer container, ClientContext context) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Failed insert of "+block+": "+e);
	}

	public short getPriorityClass(ObjectContainer container) {
		return prioClass;
	}

	public int getRetryCount() {
		// No retries.
		return 0;
	}

	public boolean send(NodeClientCore core, RequestScheduler sched, ChosenRequest req) {
		// Ignore keyNum, key, since this is a single block
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		try {
			if(logMINOR) Logger.minor(this, "Starting request: "+this);
			core.realPut(block, shouldCache());
		} catch (LowLevelPutException e) {
			sched.callFailure(this, e, req.token, NativeThread.NORM_PRIORITY, req, false);
			if(logMINOR) Logger.minor(this, "Request failed: "+this+" for "+e);
			return true;
		} finally {
			finished = true;
		}
		if(logMINOR) Logger.minor(this, "Request succeeded: "+this);
		sched.callSuccess(this, req.token, NativeThread.NORM_PRIORITY, req, false);
		return true;
	}

	public RequestClient getClient() {
		return client;
	}

	public ClientRequester getClientRequest() {
		return null;
	}

	public boolean isCancelled(ObjectContainer container) {
		return finished;
	}
	
	public boolean isEmpty(ObjectContainer container) {
		return finished;
	}

	public boolean canRemove(ObjectContainer container) {
		return true;
	}

	public void schedule() {
		finished = false; // can reschedule
		scheduler.registerInsert(this, false, false);
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		super.unregister(container, context);
	}

	public boolean shouldCache() {
		// This is only used as-is by the random reinsert from a request code. Subclasses should override!
		return false;
	}

	public synchronized Object[] allKeys(ObjectContainer container) {
		if(finished) return new Object[] {};
		return new Object[] { new Integer(0) };
	}

	public synchronized Object[] sendableKeys(ObjectContainer container) {
		if(finished) return new Object[] {};
		return new Object[] { new Integer(0) };
	}

	public synchronized Object chooseKey(KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
		if(finished) return null;
		else return new Integer(0);
	}

	public boolean isSSK() {
		return block instanceof SSKBlock;
	}
}
