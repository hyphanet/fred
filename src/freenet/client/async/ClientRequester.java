/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.node.SendableRequest;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;

/** A high level client request. A request (either fetch or put) started
 * by a Client. Has a suitable context and a URI; is fulfilled only when
 * we have followed all the redirects etc, or have an error. Can be
 * retried.
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public abstract class ClientRequester {
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(ClientRequester.class);
	}

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
	@Override
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
	protected boolean sentToNetwork;

	public void blockSetFinalized(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(blockSetFinalized) return;
			blockSetFinalized = true;
		}
		if(logMINOR)
			Logger.minor(this, "Finalized set of blocks for "+this, new Exception("debug"));
		if(persistent())
			container.store(this);
		notifyClients(container, context);
	}

	/** Add a block to our estimate of the total. Don't notify clients. */
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
		
		if(logMINOR) Logger.minor(this, "addBlock(): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks);
		if(persistent()) container.store(this);
	}

	/** Add several blocks to our estimate of the total. Don't notify clients. */
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
		
		if(logMINOR) Logger.minor(this, "addBlocks("+num+"): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks); 
		if(persistent()) container.store(this);
	}

	/** We completed a block. Count it and notify clients unless dontNotify. */
	public void completedBlock(boolean dontNotify, ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Completed block ("+dontNotify+ "): total="+totalBlocks+" success="+successfulBlocks+" failed="+failedBlocks+" fatally="+fatallyFailedBlocks+" finalised="+blockSetFinalized+" required="+minSuccessBlocks+" on "+this);
		synchronized(this) {
			if(cancelled) return;
			successfulBlocks++;
		}
		if(persistent()) container.store(this);
		if(dontNotify) return;
		notifyClients(container, context);
	}

	/** A block failed. Count it and notify our clients. */
	public void failedBlock(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			failedBlocks++;
		}
		if(persistent()) container.store(this);
		notifyClients(container, context);
	}

	/** A block failed fatally. Count it and notify our clients. */
	public void fatallyFailedBlock(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			fatallyFailedBlocks++;
		}
		if(persistent()) container.store(this);
		notifyClients(container, context);
	}

	/** Add one or more blocks to the number of requires blocks, and don't notify the clients. */
	public synchronized void addMustSucceedBlocks(int blocks, ObjectContainer container) {
		minSuccessBlocks += blocks;
		if(persistent()) container.store(this);
		if(logMINOR) Logger.minor(this, "addMustSucceedBlocks("+blocks+"): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks); 
	}

	/** Notify clients, usually via a SplitfileProgressEvent, of the current progress. */
	public abstract void notifyClients(ObjectContainer container, ClientContext context);
	
	/** Called when we first send a request to the network. Ensures that it really is the first time and
	 * passes on to innerToNetwork().
	 */
	public void toNetwork(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(sentToNetwork) return;
			sentToNetwork = true;
			if(persistent()) container.store(this);
		}
		innerToNetwork(container, context);
	}

	/** Notify clients that a request has gone to the network, for the first time, i.e. we have finished
	 * checking the datastore for at least one part of the request. */
	protected abstract void innerToNetwork(ObjectContainer container, ClientContext context);

	/** Get client context object */
	public RequestClient getClient() {
		return client;
	}

	/** Change the priority class of the request (request includes inserts here).
	 * @param newPriorityClass The new priority class for the request or insert.
	 * @param ctx The ClientContext, contains essential transient objects such as the schedulers.
	 * @param container The database. If the request is persistent, this must be non-null, and we must
	 * be running on the database thread; you should schedule a job using the DBJobRunner.
	 */
	public void setPriorityClass(short newPriorityClass, ClientContext ctx, ObjectContainer container) {
		this.priorityClass = newPriorityClass;
		ctx.getChkFetchScheduler().reregisterAll(this, container);
		ctx.getChkInsertScheduler().reregisterAll(this, container);
		ctx.getSskFetchScheduler().reregisterAll(this, container);
		ctx.getSskInsertScheduler().reregisterAll(this, container);
		if(persistent()) container.store(this);
	}

	/** Is this request persistent? */
	public boolean persistent() {
		return client.persistent();
	}

	/** Remove this request from the database */
	public void removeFrom(ObjectContainer container, ClientContext context) {
		container.activate(requests, 1);
		requests.removeFrom(container);
		container.delete(this);
	}
	
	/** When the request is activated, so is the request client, because we have to know whether we are
	 * persistent! */
	public void objectOnActivate(ObjectContainer container) {
		container.activate(client, 1);
	}

	/** Add a low-level request to the list of requests belonging to this high-level request (request here
	 * includes inserts). */
	public void addToRequests(SendableRequest req, ObjectContainer container) {
		if(persistent())
			container.activate(requests, 1);
		requests.addRequest(req, container);
		if(persistent())
			container.deactivate(requests, 1);
	}

	/** Get all known low-level requests belonging to this high-level request.
	 * @param container The database, must be non-null if this is a persistent request or persistent insert.
	 */
	public SendableRequest[] getSendableRequests(ObjectContainer container) {
		if(persistent())
			container.activate(requests, 1);
		SendableRequest[] reqs = requests.listRequests(container);
		if(persistent())
			container.deactivate(requests, 1);
		return reqs;
	}

	/** Remove a low-level request or insert from the list of known requests belonging to this 
	 * high-level request or insert. */
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
