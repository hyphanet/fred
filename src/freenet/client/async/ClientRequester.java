/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.Logger;

/** A high level client request. A request (either fetch or put) started
 * by a Client. Has a suitable context and a URI; is fulfilled only when
 * we have followed all the redirects etc, or have an error. Can be
 * retried.
 */
public abstract class ClientRequester {

	public abstract void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container);
	
	// FIXME move the priority classes from RequestStarter here
	protected short priorityClass;
	protected boolean cancelled;
	protected final RequestClient client;

	public short getPriorityClass() {
		return priorityClass;
	}

	protected ClientRequester(short priorityClass, RequestClient client) {
		this.priorityClass = priorityClass;
		this.client = client;
	}

	public synchronized void cancel() {
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
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Finalized set of blocks for "+this, new Exception("debug"));
		notifyClients();
	}

	public synchronized void addBlock() {
		if(blockSetFinalized)
			if(Logger.globalGetThreshold() > Logger.MINOR)
				Logger.error(this, "addBlock() but set finalized! on "+this);
			else
				Logger.error(this, "addBlock() but set finalized! on "+this, new Exception("error"));
		totalBlocks++;
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "addBlock(): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks); 
	}

	public synchronized void addBlocks(int num) {
		if(blockSetFinalized)
			if(Logger.globalGetThreshold() > Logger.MINOR)
				Logger.error(this, "addBlocks() but set finalized! on "+this);
			else
				Logger.error(this, "addBlocks() but set finalized! on "+this, new Exception("error"));
		totalBlocks+=num;
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "addBlocks("+num+"): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks); 
	}

	public void completedBlock(boolean dontNotify) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Completed block ("+dontNotify+ "): total="+totalBlocks+" success="+successfulBlocks+" failed="+failedBlocks+" fatally="+fatallyFailedBlocks+" finalised="+blockSetFinalized+" required="+minSuccessBlocks+" on "+this);
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
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "addMustSucceedBlocks("+blocks+"): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks); 
	}

	public abstract void notifyClients();

	/** Get client context object */
	public RequestClient getClient() {
		return client;
	}

	public void setPriorityClass(short newPriorityClass, ClientContext ctx) {
		this.priorityClass = newPriorityClass;
		ctx.chkFetchScheduler.reregisterAll(this);
		ctx.chkInsertScheduler.reregisterAll(this);
		ctx.sskFetchScheduler.reregisterAll(this);
		ctx.sskInsertScheduler.reregisterAll(this);
	}

	public boolean persistent() {
		return client.persistent();
	}

}
