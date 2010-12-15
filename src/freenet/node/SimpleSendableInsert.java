/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.async.ChosenBlock;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.client.async.ClientRequester;
import freenet.client.async.PersistentChosenBlock;
import freenet.client.async.PersistentChosenRequest;
import freenet.keys.CHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.KeyBlock;
import freenet.keys.SSKBlock;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

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
	      
        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public SimpleSendableInsert(NodeClientCore core, KeyBlock block, short prioClass) {
		super(false, false);
		this.block = block;
		this.prioClass = prioClass;
		this.client = core.node.nonPersistentClientBulk;
		if(block instanceof CHKBlock)
			scheduler = core.requestStarters.chkPutSchedulerBulk;
		else if(block instanceof SSKBlock)
			scheduler = core.requestStarters.sskPutSchedulerBulk;
		else
			throw new IllegalArgumentException("Don't know what to do with "+block);
		if(!scheduler.isInsertScheduler())
			throw new IllegalStateException("Scheduler "+scheduler+" is not an insert scheduler!");
	}
	
	public SimpleSendableInsert(KeyBlock block, short prioClass, RequestClient client, ClientRequestScheduler scheduler) {
		super(false, false);
		this.block = block;
		this.prioClass = prioClass;
		this.client = client;
		this.scheduler = scheduler;
	}
	
	@Override
	public void onSuccess(Object keyNum, ObjectContainer container, ClientContext context) {
		// Yay!
		if(logMINOR)
			Logger.minor(this, "Finished insert of "+block);
	}

	@Override
	public void onFailure(LowLevelPutException e, Object keyNum, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Failed insert of "+block+": "+e);
	}

	@Override
	public short getPriorityClass(ObjectContainer container) {
		return prioClass;
	}

	@Override
	public SendableRequestSender getSender(ObjectContainer container, ClientContext context) {
		return new SendableRequestSender() {

			public boolean send(NodeClientCore core, RequestScheduler sched, ClientContext context, ChosenBlock req) {
				// Ignore keyNum, key, since this is a single block
				try {
					if(logMINOR) Logger.minor(this, "Starting request: "+this);
					// FIXME bulk flag
					core.realPut(block, req.canWriteClientCache, Node.FORK_ON_CACHEABLE_DEFAULT, Node.PREFER_INSERT_DEFAULT, Node.IGNORE_LOW_BACKOFF_DEFAULT, false);
				} catch (LowLevelPutException e) {
					onFailure(e, req.token, null, context);
					if(logMINOR) Logger.minor(this, "Request failed: "+this+" for "+e);
					return true;
				} finally {
					finished = true;
				}
				if(logMINOR) Logger.minor(this, "Request succeeded: "+this);
				onSuccess(req.token, null, context);
				return true;
			}
		};
	}

	@Override
	public RequestClient getClient(ObjectContainer container) {
		return client;
	}

	@Override
	public ClientRequester getClientRequest() {
		return null;
	}

	@Override
	public boolean isCancelled(ObjectContainer container) {
		return finished;
	}
	
	public boolean isEmpty(ObjectContainer container) {
		return finished;
	}

	public void schedule() {
		finished = false; // can reschedule
		scheduler.registerInsert(this, false, false, null);
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		super.unregister(container, context, prioClass);
	}

	@Override
	public synchronized long countAllKeys(ObjectContainer container, ClientContext context) {
		if(finished) return 0;
		return 1;
	}

	@Override
	public synchronized long countSendableKeys(ObjectContainer container, ClientContext context) {
		if(finished) return 0;
		return 1;
	}

	@Override
	public synchronized SendableRequestItem chooseKey(KeysFetchingLocally keys, ObjectContainer container, ClientContext context) {
		if(keys.hasTransientInsert(this, NullSendableRequestItem.nullItem))
			return null;
		if(finished) return null;
		else
			return NullSendableRequestItem.nullItem;
	}

	@Override
	public boolean isSSK() {
		return block instanceof SSKBlock;
	}

	@Override
	public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, ObjectContainer container, ClientContext context) {
		// Transient-only so no makeBlocks().
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean canWriteClientCache(ObjectContainer container) {
		return false;
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		// Transient-only
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean forkOnCacheable(ObjectContainer container) {
		return Node.FORK_ON_CACHEABLE_DEFAULT;
	}

	@Override
	public void onEncode(SendableRequestItem token, ClientKey key, ObjectContainer container, ClientContext context) {
		// Ignore.
	}

	@Override
	public boolean localRequestOnly(ObjectContainer container) {
		return false;
	}

}
