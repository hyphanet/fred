package freenet.client.async;

import freenet.keys.FreenetURI;
import freenet.support.Logger;

/** A high level client request. A request (either fetch or put) started
 * by a Client. Has a suitable context and a URI; is fulfilled only when
 * we have followed all the redirects etc, or have an error. Can be 
 * retried.
 */
public abstract class ClientRequester {

	// FIXME move the priority classes from RequestStarter here
	protected short priorityClass;
	protected boolean cancelled;
	final ClientRequestScheduler chkScheduler;
	final ClientRequestScheduler sskScheduler;
	protected final Object client;
	
	public short getPriorityClass() {
		return priorityClass;
	}
	
	protected ClientRequester(short priorityClass, ClientRequestScheduler chkScheduler, ClientRequestScheduler sskScheduler, Object client) {
		this.priorityClass = priorityClass;
		this.chkScheduler = chkScheduler;
		this.sskScheduler = sskScheduler;
		this.client = client;
	}
	
	public void cancel() {
		cancelled = true;
	}
	
	public boolean isCancelled() {
		return cancelled;
	}
	
	public abstract FreenetURI getURI();
	
	public abstract boolean isFinished();
	
	/** Total number of blocks this request has tried to fetch/put. */
	protected int totalBlocks;
	/** Number of blocks we have successfully completed a fetch/put for. */
	protected int successfulBlocks;
	/** Number of blocks which have failed. */
	protected int failedBlocks;
	/** Number of blocks which have failed fatally. */
	protected int fatallyFailedBlocks;
	/** Minimum number of blocks required to succeed for success. */
	protected int minSuccessBlocks;
	/** Has totalBlocks stopped growing? */
	protected boolean blockSetFinalized;
	
	public void blockSetFinalized() {
		synchronized(this) {
			if(blockSetFinalized) return;
			blockSetFinalized = true;
		}
		Logger.minor(this, "Finalized set of blocks for "+this, new Exception("debug"));
		notifyClients();
	}
	
	public synchronized void addBlock() {
		if(blockSetFinalized)
			Logger.error(this, "addBlock() but set finalized! on "+this, new Exception("error"));
		totalBlocks++;
	}
	
	public synchronized void addBlocks(int num) {
		if(blockSetFinalized)
			Logger.error(this, "addBlock() but set finalized! on "+this, new Exception("error"));
		totalBlocks+=num;
	}
	
	public void completedBlock(boolean dontNotify) {
		Logger.minor(this, "Completed block ("+dontNotify+")");
		synchronized(this) {
			successfulBlocks++;
			if(dontNotify) return;
		}
		notifyClients();
	}
	
	public void failedBlock() {
		synchronized(this) {
			failedBlocks++;
		}
		notifyClients();
	}
	
	public void fatallyFailedBlock() {
		synchronized(this) {
			fatallyFailedBlocks++;
		}
		notifyClients();
	}
	
	public synchronized void addMustSucceedBlocks(int blocks) {
		minSuccessBlocks += blocks;
	}
	
	public abstract void notifyClients();

	/** Get client context object */
	public Object getClient() {
		return client;
	}

	public void setPriorityClass(short newPriorityClass) {
		this.priorityClass = newPriorityClass;
		chkScheduler.reregisterAll(this);
		sskScheduler.reregisterAll(this);
	}

}
