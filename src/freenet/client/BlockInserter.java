package freenet.client;

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
	
	/**
	 * Create a BlockInserter.
	 * @param bucket The data to insert, or null if it will be filled in later.
	 * @param num The block number in the splitfile.
	 */
	public BlockInserter(Bucket bucket, int num, RetryTracker tracker, InserterContext ctx) {
		super(tracker, num);
		this.data = bucket;
		if(bucket == null) throw new NullPointerException();
		succeeded = false;
		this.ctx = ctx;
		block = new InsertBlock(bucket, null, FreenetURI.EMPTY_CHK_URI);
	}

	public synchronized void setData(Bucket data) {
		if(this.data != null) throw new IllegalArgumentException("Cannot set data when already have data");
		this.data = data;
	}

	public void kill() {
		// Do nothing, for now.
	}

	public FreenetURI getURI() {
		return uri;
	}

	public void run() {
		try {
			realRun();
		} catch (Throwable t) {
			fatalError(t, InserterException.INTERNAL_ERROR);
		} finally {
			completedTries++;
		}
	}
	
	private void realRun() {
		FileInserter inserter = new FileInserter(ctx);
		try {
			uri = inserter.run(block, false);
			succeeded = true;
			tracker.success(this);
		} catch (InserterException e) {
			switch(e.mode) {
			case InserterException.REJECTED_OVERLOAD:
			case InserterException.ROUTE_NOT_FOUND:
				nonfatalError(e, e.mode);
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

	private void fatalError(Throwable e, int code) {
		Logger.normal(this, "Giving up on block: "+this+": "+e);
		completedTries = -1;
		tracker.fatalError(this, code);
	}

	private void nonfatalError(Exception e, int code) {
		Logger.minor(this, "Non-fatal error on "+this+": "+e);
		tracker.nonfatalError(this, code);
	}
	
	protected void checkStartable() {
		if(succeeded)
			throw new IllegalStateException("Already inserted block");
	}
}
