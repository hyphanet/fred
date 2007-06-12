package freenet.client.async;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Vector;

import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.SSKBlock;
import freenet.node.LowLevelPutException;
import freenet.node.SimpleSendableInsert;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class BinaryBlobInserter implements ClientPutState {

	final ClientPutter parent;
	final Object clientContext;
	final MySendableInsert[] inserters;
	final FailureCodeTracker errors;
	final int maxRetries;
	final int consecutiveRNFsCountAsSuccess;
	private boolean logMINOR;
	private int completedBlocks;
	private int succeededBlocks;
	private boolean fatal;
	final InsertContext ctx;
	
	BinaryBlobInserter(Bucket blob, ClientPutter parent, Object clientContext, boolean tolerant, short prioClass, InsertContext ctx) 
	throws IOException, BinaryBlobFormatException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.ctx = ctx;
		this.maxRetries = ctx.maxInsertRetries;
		this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
		this.parent = parent;
		this.clientContext = clientContext;
		this.errors = new FailureCodeTracker(true);
		DataInputStream dis = new DataInputStream(blob.getInputStream());
		
		BlockSet blocks = new SimpleBlockSet();
		
		BinaryBlob.readBinaryBlob(dis, blocks, tolerant);
		
		Vector myInserters = new Vector();
		Iterator i = blocks.keys().iterator();
		
		int x=0;
		while(i.hasNext()) {
			Key key = (Key) i.next();
			KeyBlock block = blocks.get(key);
			MySendableInsert inserter =
				new MySendableInsert(x++, block, prioClass, getScheduler(block), clientContext);
			myInserters.add(inserter);
		}
		
		inserters = (MySendableInsert[]) myInserters.toArray(new MySendableInsert[myInserters.size()]);
		parent.addMustSucceedBlocks(inserters.length);
		parent.notifyClients();
	}
	
	private ClientRequestScheduler getScheduler(KeyBlock block) {
		if(block instanceof CHKBlock)
			return parent.chkScheduler;
		else if(block instanceof SSKBlock)
			return parent.sskScheduler;
		else throw new IllegalArgumentException("Unknown block type "+block.getClass()+" : "+block);
	}

	public void cancel() {
		for(int i=0;i<inserters.length;i++) {
			if(inserters[i] != null)
				inserters[i].cancel();
		}
		parent.onFailure(new InsertException(InsertException.CANCELLED), this);
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public SimpleFieldSet getProgressFieldset() {
		// FIXME not supported
		return null;
	}

	public Object getToken() {
		return clientContext;
	}

	public void schedule() throws InsertException {
		for(int i=0;i<inserters.length;i++) {
			inserters[i].schedule();
		}
	}
	
	class MySendableInsert extends SimpleSendableInsert {

		final int blockNum;
		private int consecutiveRNFs;
		private int retries;
		
		public MySendableInsert(int i, KeyBlock block, short prioClass, ClientRequestScheduler scheduler, Object client) {
			super(block, prioClass, client, scheduler);
			this.blockNum = i;
		}
		
		public void onSuccess() {
			synchronized(this) {
				if(inserters[blockNum] == null) return;
				inserters[blockNum] = null;
				completedBlocks++;
				succeededBlocks++;
			}
			parent.completedBlock(false);
			maybeFinish();
		}

		// FIXME duplicated code from SingleBlockInserter
		// FIXME combine it somehow
		public void onFailure(LowLevelPutException e) {
			synchronized(BinaryBlobInserter.this) {
				if(inserters[blockNum] == null) return;
			}
			if(parent.isCancelled()) {
				fail(new InsertException(InsertException.CANCELLED), true);
				return;
			}
			logMINOR = Logger.shouldLog(Logger.MINOR, BinaryBlobInserter.this);
			switch(e.code) {
			case LowLevelPutException.COLLISION:
				fail(new InsertException(InsertException.COLLISION), false);
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
					onSuccess();
					return;
				}
			} else
				consecutiveRNFs = 0;
			if(logMINOR) Logger.minor(this, "Failed: "+e);
			retries++;
			if((retries > maxRetries) && (maxRetries != -1)) {
				fail(InsertException.construct(errors), false);
				return;
			}
			schedule();
		}

		private void fail(InsertException e, boolean fatal) {
			synchronized(BinaryBlobInserter.this) {
				if(inserters[blockNum] == null) return;
				inserters[blockNum] = null;
				completedBlocks++;
				if(fatal) BinaryBlobInserter.this.fatal = true;
			}
			if(fatal)
				parent.fatallyFailedBlock();
			else
				parent.failedBlock();
			maybeFinish();
		}
		
		public boolean shouldCache() {
			return ctx.cacheLocalRequests;
		}
	}

	public void maybeFinish() {
		boolean success;
		boolean wasFatal;
		synchronized(this) {
			if(completedBlocks != inserters.length)
				return;
			success = completedBlocks == succeededBlocks;
			wasFatal = fatal;
		}
		if(success) {
			parent.onSuccess(this);
		} else if(wasFatal)
			parent.onFailure(new InsertException(InsertException.FATAL_ERRORS_IN_BLOCKS, errors, null), this);
		else
			parent.onFailure(new InsertException(InsertException.TOO_MANY_RETRIES_IN_BLOCKS, errors, null), this);
	}
	
}
