/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.node.SendableRequest;
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
	protected final SendableRequestSet requests;

	public short getPriorityClass() {
		return priorityClass;
	}

	protected ClientRequester(short priorityClass, RequestClient client) {
		this.priorityClass = priorityClass;
		this.client = client;
		if(client == null)
			throw new NullPointerException();
		hashCode = super.hashCode(); // the old object id will do fine, as long as we ensure it doesn't change!
		requests = persistent() ? new PersistentSendableRequestSet() : new TransientSendableRequestSet();
	}

	public synchronized boolean cancel() {
		boolean ret = cancelled;
		cancelled = true;
		return ret;
	}
	
	public abstract void cancel(ObjectContainer container, ClientContext context);

	public boolean isCancelled() {
		return cancelled;
	}

	public abstract FreenetURI getURI();

	public abstract boolean isFinished();
	
	private final int hashCode;
	
	/**
	 * We need a hash code that persists across restarts.
	 */
	public int hashCode() {
		return hashCode;
	}

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

	public void blockSetFinalized(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(blockSetFinalized) return;
			blockSetFinalized = true;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Finalized set of blocks for "+this, new Exception("debug"));
		if(persistent())
			container.store(this);
		notifyClients(container, context);
	}

	public void addBlock(ObjectContainer container) {
		boolean wasFinalized;
		synchronized (this) {
			totalBlocks++;
			wasFinalized = blockSetFinalized;
		}

		if (wasFinalized) {
			if (Logger.globalGetThreshold() > Logger.MINOR)
				Logger.error(this, "addBlock() but set finalized! on " + this);
			else
				Logger.error(this, "addBlock() but set finalized! on " + this, new Exception("error"));
		}
		
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "addBlock(): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks);
		if(persistent()) container.store(this);
	}

	public void addBlocks(int num, ObjectContainer container) {
		boolean wasFinalized;
		synchronized (this) {
			totalBlocks += num;
			wasFinalized = blockSetFinalized;
		}

		if (wasFinalized) {
			if(Logger.globalGetThreshold() > Logger.MINOR)
				Logger.error(this, "addBlocks() but set finalized! on "+this);
			else
				Logger.error(this, "addBlocks() but set finalized! on "+this, new Exception("error"));
		}
		
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "addBlocks("+num+"): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks); 
		if(persistent()) container.store(this);
	}

	public void completedBlock(boolean dontNotify, ObjectContainer container, ClientContext context) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Completed block ("+dontNotify+ "): total="+totalBlocks+" success="+successfulBlocks+" failed="+failedBlocks+" fatally="+fatallyFailedBlocks+" finalised="+blockSetFinalized+" required="+minSuccessBlocks+" on "+this);
		synchronized(this) {
			if(cancelled) return;
			successfulBlocks++;
		}
		if(persistent()) container.store(this);
		if(dontNotify) return;
		notifyClients(container, context);
	}

	public void failedBlock(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			failedBlocks++;
		}
		if(persistent()) container.store(this);
		notifyClients(container, context);
	}

	public void fatallyFailedBlock(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			fatallyFailedBlocks++;
		}
		if(persistent()) container.store(this);
		notifyClients(container, context);
	}

	public synchronized void addMustSucceedBlocks(int blocks, ObjectContainer container) {
		minSuccessBlocks += blocks;
		if(persistent()) container.store(this);
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "addMustSucceedBlocks("+blocks+"): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks); 
	}

	public abstract void notifyClients(ObjectContainer container, ClientContext context);

	/** Get client context object */
	public RequestClient getClient() {
		return client;
	}

	public void setPriorityClass(short newPriorityClass, ClientContext ctx, ObjectContainer container) {
		this.priorityClass = newPriorityClass;
		ctx.getChkFetchScheduler().reregisterAll(this, container);
		ctx.getChkInsertScheduler().reregisterAll(this, container);
		ctx.getSskFetchScheduler().reregisterAll(this, container);
		ctx.getSskInsertScheduler().reregisterAll(this, container);
		if(persistent()) container.store(this);
	}

	public boolean persistent() {
		return client.persistent();
	}


	public void removeFrom(ObjectContainer container, ClientContext context) {
		container.activate(requests, 1);
		requests.removeFrom(container);
		container.delete(this);
	}
	
	public void objectOnActivate(ObjectContainer container) {
		container.activate(client, 1);
	}

	public void addToRequests(SendableRequest req, ObjectContainer container) {
		if(persistent())
			container.activate(requests, 1);
		requests.addRequest(req, container);
		if(persistent())
			container.deactivate(requests, 1);
	}

	public SendableRequest[] getSendableRequests(ObjectContainer container) {
		if(persistent())
			container.activate(requests, 1);
		SendableRequest[] reqs = requests.listRequests(container);
		if(persistent())
			container.deactivate(requests, 1);
		return reqs;
	}

	public void removeFromRequests(SendableRequest req, ObjectContainer container, boolean dontComplain) {
		if(persistent())
			container.activate(requests, 1);
		if(!requests.removeRequest(req, container) && !dontComplain) {
			Logger.error(this, "Not in request list for "+this+": "+req);
		}
		if(persistent())
			container.deactivate(requests, 1);
	}

}
