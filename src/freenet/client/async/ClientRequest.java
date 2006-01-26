package freenet.client.async;

import freenet.keys.FreenetURI;
import freenet.support.Logger;

/** A high level client request. A request (either fetch or put) started
 * by a Client. Has a suitable context and a URI; is fulfilled only when
 * we have followed all the redirects etc, or have an error. Can be 
 * retried.
 */
public abstract class ClientRequest {

	// FIXME move the priority classes from RequestStarter here
	protected short priorityClass;
	protected boolean cancelled;
	final ClientRequestScheduler scheduler;
	
	public short getPriorityClass() {
		return priorityClass;
	}
	
	protected ClientRequest(short priorityClass, ClientRequestScheduler scheduler) {
		this.priorityClass = priorityClass;
		this.scheduler = scheduler;
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
	
	public synchronized void addBlock() {
		totalBlocks++;
	}
	
	public synchronized void addBlocks(int num) {
		totalBlocks+=num;
	}
	
	public synchronized void completedBlock(boolean dontNotify) {
		Logger.minor(this, "Completed block ("+dontNotify+")");
		successfulBlocks++;
		if(!dontNotify)
			notifyClients();
	}
	
	public synchronized void failedBlock() {
		failedBlocks++;
		notifyClients();
	}
	
	public synchronized void fatallyFailedBlock() {
		fatallyFailedBlocks++;
		notifyClients();
	}
	
	public synchronized void addMustSucceedBlocks(int blocks) {
		minSuccessBlocks += blocks;
	}
	
	public abstract void notifyClients();
}
