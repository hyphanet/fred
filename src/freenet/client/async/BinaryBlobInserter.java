package freenet.client.async;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;

import com.db4o.ObjectContainer;

import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.SSKBlock;
import freenet.node.LowLevelPutException;
import freenet.node.RequestClient;
import freenet.node.SimpleSendableInsert;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public class BinaryBlobInserter implements ClientPutState {

	final ClientPutter parent;
	final RequestClient clientContext;
	final MySendableInsert[] inserters;
	final FailureCodeTracker errors;
	final int maxRetries;
	final int consecutiveRNFsCountAsSuccess;
	private boolean logMINOR;
	private int completedBlocks;
	private int succeededBlocks;
	private boolean fatal;
	final InsertContext ctx;
	final boolean realTimeFlag;

	BinaryBlobInserter(Bucket blob, ClientPutter parent, RequestClient clientContext, boolean tolerant, short prioClass, InsertContext ctx, ClientContext context, ObjectContainer container)
	throws IOException, BinaryBlobFormatException {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		this.ctx = ctx;
		this.maxRetries = ctx.maxInsertRetries;
		this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
		this.parent = parent;
		this.clientContext = clientContext;
		this.errors = new FailureCodeTracker(true);
		this.realTimeFlag = clientContext.realTimeFlag();
		DataInputStream dis = new DataInputStream(blob.getInputStream());

		BlockSet blocks = new SimpleBlockSet();

		try {
			BinaryBlob.readBinaryBlob(dis, blocks, tolerant);
		} finally {
			dis.close();
		}

		ArrayList<MySendableInsert> myInserters = new ArrayList<MySendableInsert>();
		int x=0;
		for(Key key: blocks.keys()) {
			KeyBlock block = blocks.get(key);
			MySendableInsert inserter =
				new MySendableInsert(x++, block, prioClass, getScheduler(block, container, context), clientContext);
			myInserters.add(inserter);
		}

		inserters = myInserters.toArray(new MySendableInsert[myInserters.size()]);
		parent.addMustSucceedBlocks(inserters.length, container);
		parent.notifyClients(container, context);
	}

	private ClientRequestScheduler getScheduler(KeyBlock block, ObjectContainer container, ClientContext context) {
		if(block instanceof CHKBlock)
			return context.getChkInsertScheduler(realTimeFlag);
		else if(block instanceof SSKBlock)
			return context.getSskInsertScheduler(realTimeFlag);
		else throw new IllegalArgumentException("Unknown block type "+block.getClass()+" : "+block);
	}

	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		for(MySendableInsert inserter: inserters) {
			if(inserter != null)
				inserter.cancel(container, context);
		}
		parent.onFailure(new InsertException(InsertException.CANCELLED), this, container, context);
	}

	@Override
	public BaseClientPutter getParent() {
		return parent;
	}

	@Override
	public Object getToken() {
		return clientContext;
	}

	@Override
	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		for(MySendableInsert inserter: inserters) {
			inserter.schedule();
		}
	}

	class MySendableInsert extends SimpleSendableInsert {

		final int blockNum;
		private int consecutiveRNFs;
		private int retries;

		public MySendableInsert(int i, KeyBlock block, short prioClass, ClientRequestScheduler scheduler, RequestClient client) {
			super(block, prioClass, client, scheduler);
			this.blockNum = i;
		}

		@Override
		public void onSuccess(Object keyNum, ObjectContainer container, ClientContext context) {
			synchronized(this) {
				if(inserters[blockNum] == null) return;
				inserters[blockNum] = null;
				completedBlocks++;
				succeededBlocks++;
			}
			parent.completedBlock(false, container, context);
			maybeFinish(container, context);
		}

		// FIXME duplicated code from SingleBlockInserter
		// FIXME combine it somehow
		@Override
		public void onFailure(LowLevelPutException e, Object keyNum, ObjectContainer container, ClientContext context) {
			synchronized(BinaryBlobInserter.this) {
				if(inserters[blockNum] == null) return;
			}
			if(parent.isCancelled()) {
				fail(new InsertException(InsertException.CANCELLED), true, container, context);
				return;
			}
			logMINOR = Logger.shouldLog(LogLevel.MINOR, BinaryBlobInserter.this);
			switch(e.code) {
			case LowLevelPutException.COLLISION:
				fail(new InsertException(InsertException.COLLISION), false, container, context);
				break;
			case LowLevelPutException.INTERNAL_ERROR:
				errors.inc(InsertException.INTERNAL_ERROR);
				break;
			case LowLevelPutException.REJECTED_OVERLOAD:
				errors.inc(InsertException.REJECTED_OVERLOAD);
				break;
			case LowLevelPutException.ROUTE_NOT_FOUND:
				errors.inc(InsertException.ROUTE_NOT_FOUND);
				break;
			case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
				errors.inc(InsertException.ROUTE_REALLY_NOT_FOUND);
				break;
			default:
				Logger.error(this, "Unknown LowLevelPutException code: "+e.code);
				errors.inc(InsertException.INTERNAL_ERROR);
			}
			if(e.code == LowLevelPutException.ROUTE_NOT_FOUND) {
				consecutiveRNFs++;
				if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+consecutiveRNFs+" / "+consecutiveRNFsCountAsSuccess);
				if(consecutiveRNFs == consecutiveRNFsCountAsSuccess) {
					if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+consecutiveRNFs+" - counting as success");
					onSuccess(keyNum, container, context);
					return;
				}
			} else
				consecutiveRNFs = 0;
			if(logMINOR) Logger.minor(this, "Failed: "+e);
			retries++;
			if((retries > maxRetries) && (maxRetries != -1)) {
				fail(InsertException.construct(errors), false, container, context);
				return;
			}
			// Retry *this block*
			this.schedule();
		}

		private void fail(InsertException e, boolean fatal, ObjectContainer container, ClientContext context) {
			synchronized(BinaryBlobInserter.this) {
				if(inserters[blockNum] == null) return;
				inserters[blockNum] = null;
				completedBlocks++;
				if(fatal) BinaryBlobInserter.this.fatal = true;
			}
			if(fatal)
				parent.fatallyFailedBlock(container, context);
			else
				parent.failedBlock(container, context);
			maybeFinish(container, context);
		}

	}

	public void maybeFinish(ObjectContainer container, ClientContext context) {
		boolean success;
		boolean wasFatal;
		synchronized(this) {
			if(completedBlocks != inserters.length)
				return;
			success = completedBlocks == succeededBlocks;
			wasFatal = fatal;
		}
		if(success) {
			parent.onSuccess(this, container, context);
		} else if(wasFatal)
			parent.onFailure(new InsertException(InsertException.FATAL_ERRORS_IN_BLOCKS, errors, null), this, container, context);
		else
			parent.onFailure(new InsertException(InsertException.TOO_MANY_RETRIES_IN_BLOCKS, errors, null), this, container, context);
	}

	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		// FIXME: Persistent blob inserts are not supported.
		throw new UnsupportedOperationException();
	}

}
