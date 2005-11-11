package freenet.client;

import freenet.client.events.BlockInsertErrorEvent;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.Logger;

/**
 * Inserts a single splitfile block.
 */
public class BlockInserter extends StdSplitfileBlock implements Runnable {

	private boolean succeeded;
	private int completedTries;
	private final InserterContext ctx;
	private final InsertBlock block;
	private FreenetURI uri;
	private final boolean getCHKOnly;
	
	/**
	 * Create a BlockInserter.
	 * @param bucket The data to insert, or null if it will be filled in later.
	 * @param num The block number in the splitfile.
	 */
	public BlockInserter(Bucket bucket, int num, RetryTracker tracker, InserterContext ctx, boolean getCHKOnly) {
		super(tracker, num, bucket);
		succeeded = false;
		this.ctx = ctx;
		block = new InsertBlock(bucket, null, FreenetURI.EMPTY_CHK_URI);
		this.getCHKOnly = getCHKOnly;
		Logger.minor(this, "Created "+this);
	}

	public synchronized void setData(Bucket data) {
		if(this.fetchedData != null) throw new IllegalArgumentException("Cannot set data when already have data");
		block.data = data;
		super.setData(data);
	}

	public void kill() {
		// Do nothing, for now.
	}

	public String toString() {
		return super.toString()+" succeeded="+succeeded+" tries="+completedTries+" uri="+uri;
	}
	
	public FreenetURI getURI() {
		return uri;
	}

	public void run() {
		try {
			if(fetchedData == null)
				throw new NullPointerException();
			realRun();
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t+" on "+this, t);
			fatalError(t, InserterException.INTERNAL_ERROR);
		} finally {
			completedTries++;
		}
	}
	
	private void realRun() {
		FileInserter inserter = new FileInserter(ctx);
		try {
			if(uri == null && !getCHKOnly)
				uri = inserter.run(block, false, true);
			uri = inserter.run(block, false, getCHKOnly);
			succeeded = true;
			tracker.success(this);
		} catch (InserterException e) {
			switch(e.mode) {
			case InserterException.REJECTED_OVERLOAD:
			case InserterException.ROUTE_NOT_FOUND:
				nonfatalError(e, e.mode);
				return;
			case InserterException.INTERNAL_ERROR:
			case InserterException.BUCKET_ERROR:
				fatalError(e, e.mode);
				return;
			case InserterException.FATAL_ERRORS_IN_BLOCKS:
			case InserterException.TOO_MANY_RETRIES_IN_BLOCKS:
				// Huh?
				Logger.error(this, "Got error inserting blocks ("+e.mode+") while inserting a block - WTF?");
				fatalError(e, InserterException.INTERNAL_ERROR);
				return;
			case InserterException.INVALID_URI:
				Logger.error(this, "Got invalid URI error but URI was CHK@ in block insert");
				fatalError(e, InserterException.INTERNAL_ERROR);
				return;
			default:
				Logger.error(this, "Unknown insert error "+e.mode+" while inserting a block");
				fatalError(e, InserterException.INTERNAL_ERROR);
				return;
			}
			// FIXME add more cases as we create them
		}
		
	}

	private void fatalError(InserterException e, int code) {
		Logger.normal(this, "Giving up on block: "+this+": "+e);
		tracker.fatalError(this, code);
		ctx.eventProducer.produceEvent(new BlockInsertErrorEvent(e, uri, completedTries));
	}

	private void fatalError(Throwable t, int code) {
		fatalError(new InserterException(code, t.toString()), code);
	}

	private void nonfatalError(InserterException e, int code) {
		Logger.minor(this, "Non-fatal error on "+this+": "+e);
		tracker.nonfatalError(this, code);
		ctx.eventProducer.produceEvent(new BlockInsertErrorEvent(e, uri, completedTries));
	}
	
	protected void checkStartable() {
		if(succeeded)
			throw new IllegalStateException("Already inserted block");
	}

	public int getRetryCount() {
		return completedTries;
	}
}
