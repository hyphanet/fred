/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.BulkCallFailureItem;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.node.SendableRequestSender;
import freenet.node.SupportsBulkCallFailure;
import freenet.support.ListUtils;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * A persistent SendableRequest chosen by ClientRequestScheduler. In order to minimize database I/O 
 * (and hence disk I/O and object churn), we select the entire SendableRequest, including all blocks 
 * on it. We keep it in RAM, until all blocks have succeeded/failed. Then we call all relevant 
 * callbacks in a single transaction.
 * @author toad
 */
public class PersistentChosenRequest {

	/** The request object */
	public transient final SendableRequest request;
	/** Priority when we selected it */
	public transient final short prio;
	public transient final boolean localRequestOnly;
	public transient final boolean ignoreStore;
	public transient final boolean canWriteClientCache;
	public transient final boolean forkOnCacheable;
	public transient final boolean realTimeFlag;
	public transient final ArrayList<PersistentChosenBlock> blocksNotStarted;
	public transient final HashSet<PersistentChosenBlock> blocksStarted;
	public transient final HashSet<PersistentChosenBlock> blocksFinished;
	public final RequestScheduler scheduler;
	public final SendableRequestSender sender;
	private boolean logMINOR;
	private boolean finished;
	
	PersistentChosenRequest(SendableRequest req, short prio, ObjectContainer container, RequestScheduler sched, ClientContext context) throws NoValidBlocksException {
		request = req;
		this.prio = prio;
		if(req instanceof SendableGet) {
			SendableGet sg = (SendableGet) req;
			FetchContext ctx = sg.getContext(container);
			if(container != null)
				container.activate(ctx, 1);
			localRequestOnly = ctx.localRequestOnly;
			ignoreStore = ctx.ignoreStore;
			canWriteClientCache = ctx.canWriteClientCache;
			realTimeFlag = sg.realTimeFlag();
			forkOnCacheable = false; // Doesn't exist for requests
		} else if(req instanceof SendableInsert) {
			SendableInsert sg = (SendableInsert) req;
			localRequestOnly = sg.localRequestOnly(container);
			canWriteClientCache = sg.canWriteClientCache(container);
			ignoreStore = false;
			forkOnCacheable = sg.forkOnCacheable(container);
			realTimeFlag = sg.realTimeFlag();
		} else throw new IllegalStateException("Creating a PersistentChosenRequest for "+req);

		this.scheduler = sched;
		// Fill up blocksNotStarted
		boolean reqActive = container.ext().isActive(req);
		if(!reqActive)
			container.activate(req, 1);
		KeysFetchingLocally keys = sched.fetchingKeys();
		List<PersistentChosenBlock> candidates = req.makeBlocks(this, sched, keys, container, context);
		if(candidates == null) {
			if(!reqActive) container.deactivate(req, 1);
			throw new NoValidBlocksException();
		}

		// These three structures will contain up to 256 blocks.
		// At this size the difference between hashtables and arrays is IMHO marginal: 
		// Caching effects could easily make arrays faster.
		// We need to be able to select one randomly from blocksNotStarted, so we use an array.
		blocksNotStarted = new ArrayList<PersistentChosenBlock>(candidates.size());
		// Whereas these two we only need to be able to add and remove from quickly, so we use a HashSet.
		blocksStarted = new HashSet<PersistentChosenBlock>(candidates.size() * 2);
		blocksFinished = new HashSet<PersistentChosenBlock>(candidates.size() * 2);
		
		blocksNotStarted.addAll(candidates);
		sender = req.getSender(container, context);
		if(!reqActive)
			container.deactivate(req, 1);
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
	}

	void onFinished(PersistentChosenBlock block, ClientContext context) {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "onFinished() on "+this+" for "+block, new Exception("debug"));
		synchronized(this) {
			// Remove by pointer
			
			while(blocksNotStarted.remove(block)) { // It's an ArrayList so we must loop.
				Logger.error(this, "Block finished but was in blocksNotStarted: "+block+" for "+this, new Exception("error"));
			}

			blocksStarted.remove(block);
			
			if(blocksFinished.contains(block)) {
					Logger.error(this, "Block already in blocksFinished: "+block+" for "+this);
					return;
				}
			blocksFinished.add(block);
			if(!(blocksNotStarted.isEmpty() && blocksStarted.isEmpty())) {
				if(logMINOR)
					Logger.minor(this, "Not finishing yet: blocks not started: "+blocksNotStarted.size()+" started: "+blocksStarted.size()+" finished: "+blocksFinished.size()+" for "+this);
				return;
			}
		}
		// All finished.
		try {
			context.jobRunner.queue(new DBJob() {

				@Override
				public boolean run(ObjectContainer container, ClientContext context) {
					finish(container, context, false, false);
					return true;
				}
				
			}, NativeThread.NORM_PRIORITY + 1, false);
		} catch (DatabaseDisabledException e) {
			// Impossible.
			// Can't do anything, haven't lost anything.
		}
	}

	private void finish(ObjectContainer container, ClientContext context, boolean dumping, boolean alreadyActive) {
		if(!container.ext().isStored(request)) {
			if(logMINOR) Logger.minor(this, "Request apparently already deleted: "+request+" on "+this);
			scheduler.removeRunningRequest(request, container);
			return;
		}
		if((!alreadyActive) && container.ext().isActive(request))
			Logger.warning(this, "ALREADY ACTIVATED: "+request, new Exception("debug"));
		if(!alreadyActive)
			container.activate(request, 1);
		Logger.normal(this, "Finishing "+this+" for "+request);
		// Call all the callbacks.
		PersistentChosenBlock[] finishedBlocks;
		int startedSize;
		synchronized(this) {
			if(finished) {
				if(blocksFinished.isEmpty()) {
					// Okay...
					if(!alreadyActive)
						container.deactivate(request, 1);
					// Don't removeRunningRequest, because we've already done that.
					return;
				} else {
					Logger.error(this, "Finished but blocksFinished not empty on "+this, new Exception("debug"));
					// Process the blocks...
				}
			}
			startedSize = blocksStarted.size();
			if(startedSize > 0) {
				Logger.error(this, "Still waiting for callbacks on "+this+" for "+startedSize+" blocks");
				// Wait... if we set finished, we have to process them now, and
				// we can't process them now because we haven't had the callbacks,
				// we don't know what the outcome will be.
				// Don't removeRunningRequest, because we're not finished yet.
				return;
			}
			finished = true;
			finishedBlocks = blocksFinished.toArray(new PersistentChosenBlock[blocksFinished.size()]);
		}
		if(finishedBlocks.length == 0) {
			if(!dumping)
				Logger.error(this, "No finished blocks in finish() on "+this);
			else if(logMINOR)
				Logger.minor(this, "No finished blocks in finish() on "+this);
			// Remove from running requests, we won't be called.
			scheduler.removeRunningRequest(request, container);
			if(!alreadyActive)
				container.deactivate(request, 1);
			return;
		}
		if(request instanceof SendableGet) {
			boolean supportsBulk = request instanceof SupportsBulkCallFailure;
			List<BulkCallFailureItem> bulkFailItems = null;
			for(PersistentChosenBlock block : finishedBlocks) {
				if(!block.fetchSucceeded()) {
					LowLevelGetException e = block.failedGet();
					if(supportsBulk) {
						if(bulkFailItems == null)
							bulkFailItems = new ArrayList<BulkCallFailureItem>();
						bulkFailItems.add(new BulkCallFailureItem(e, block.token));
					} else {
						((SendableGet)request).onFailure(e, block.token, container, context);
					}
				}
			}
			if(bulkFailItems != null) {
				((SupportsBulkCallFailure)request).onFailure(bulkFailItems.toArray(new BulkCallFailureItem[bulkFailItems.size()]), container, context);
			}
		} else /*if(request instanceof SendableInsert)*/ {
			container.activate(request, 1);
			for(PersistentChosenBlock block : finishedBlocks) {
				ClientKey key = block.getGeneratedKey();
				if(key != null) {
					((SendableInsert)request).onEncode(block.token, key, container, context);
				}
				if(block.insertSucceeded()) {
					((SendableInsert)request).onSuccess(block.token, container, context);
				} else {
					((SendableInsert)request).onFailure(block.failedPut(), block.token, container, context);
				}
			}
		}
		scheduler.removeRunningRequest(request, container);
		if(request instanceof SendableInsert) {
			// More blocks may have been added, because splitfile inserts
			// do not separate retries into separate SendableInsert's.
			if(!container.ext().isActive(request))
				container.activate(request, 1);
			if((!((SendableInsert)request).isEmpty(container)) && (!request.isCancelled(container))) {
				request.getScheduler(container, context).maybeAddToStarterQueue(request, container, null);
				request.getScheduler(container, context).wakeStarter();
			}
		}
		if(!alreadyActive)
			container.deactivate(request, 1);
	}

	public ChosenBlock grabNotStarted(Random random, RequestScheduler sched) {
		ArrayList<PersistentChosenBlock> dumped = null;
		try {
			synchronized(this) {
				while(true) {
					if (blocksNotStarted.isEmpty()) return null;
					PersistentChosenBlock ret = ListUtils.removeRandomBySwapLastSimple(random, blocksNotStarted);
					Key key = ret.key;
					if(key != null && sched.hasFetchingKey(key, null, false, null)) {
						// Already fetching; remove from list.
						if(dumped == null) dumped = new ArrayList<PersistentChosenBlock>();
						dumped.add(ret);
						continue;
					}
					blocksStarted.add(ret);
					return ret;
				}
			}
		} finally {
			if(dumped != null) {
				for(PersistentChosenBlock block : dumped)
					block.onDumped();
			}
		}
	}

	public synchronized int sizeNotStarted() {
		return blocksNotStarted.size();
	}

	public void onDumped(ClientRequestSchedulerCore core, ObjectContainer container, boolean reqAlreadyActive) {
		if(logMINOR)
			Logger.minor(this, "Dumping "+this);
		scheduler.removeRunningRequest(request, container);
		boolean wasStarted;
		PersistentChosenBlock[] blocks;
		synchronized(this) {
			blocks = blocksNotStarted.toArray(new PersistentChosenBlock[blocksNotStarted.size()]);
			blocksNotStarted.clear();
			wasStarted = !blocksStarted.isEmpty();
		}
		for(PersistentChosenBlock block : blocks)
			block.onDumped();
		if(!wasStarted) {
			if(logMINOR) Logger.minor(this, "Finishing immediately in onDumped() as nothing pending: "+this);
			finish(container, core.sched.clientContext, true, reqAlreadyActive);
		}
	}

	public synchronized void pruneDuplicates(ClientRequestScheduler sched) {
		for(Iterator<PersistentChosenBlock> iter = blocksNotStarted.iterator(); iter.hasNext();) {
			PersistentChosenBlock block = iter.next();
			Key key = block.key;
			if(key == null) continue;
			if(sched.hasFetchingKey(key, null, false, null)) {
				iter.remove();
				if(logMINOR) Logger.minor(this, "Pruned duplicate "+block+" from "+this);
			}
		}
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Trying to store a PersistentChosenRequest!", new Exception("error"));
		return false;
	}
	
	public boolean objectCanUpdate(ObjectContainer container) {
		Logger.error(this, "Trying to store a PersistentChosenRequest!", new Exception("error"));
		return false;
	}
	
	public boolean objectCanActivate(ObjectContainer container) {
		Logger.error(this, "Trying to store a PersistentChosenRequest!", new Exception("error"));
		return false;
	}
	
	public boolean objectCanDeactivate(ObjectContainer container) {
		Logger.error(this, "Trying to store a PersistentChosenRequest!", new Exception("error"));
		return false;
	}
	
}
