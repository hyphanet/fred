/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Date;
import java.util.WeakHashMap;

import freenet.crypt.ChecksumChecker;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.node.SendableRequest;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.ResumeFailedException;

/** A high level request or insert. This may create any number of low-level requests of inserts,
 * for example a request may follow redirects, download splitfiles and unpack containers, while an
 * insert (for a file or a freesite) may also have to insert many blocks. A high-level request is
 * created by a client, has a FetchContext or InsertContext for configuration. Compare to 
 * @see SendableRequest for a low-level request (which may still be multiple actual requests or 
 * inserts).
 * WARNING: Changing non-transient members on classes that are Serializable can result in 
 * restarting downloads or losing uploads.
 */
public abstract class ClientRequester implements Serializable, ClientRequestSchedulerGroup {

    private static final long serialVersionUID = 1L;
    private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(ClientRequester.class);
	}

	public abstract void onTransition(ClientGetState oldState, ClientGetState newState, ClientContext context);
	
	// FIXME move the priority classes from RequestStarter here
	/** Priority class of the request or insert. */
	protected short priorityClass;
	/** Whether this is a real-time request */
	protected final boolean realTimeFlag;
	/** Has the request or insert been cancelled? */
	protected boolean cancelled;
	/** The RequestClient, used to determine whether this request is 
	 * persistent, and also we round-robin between different RequestClient's
	 * in scheduling within a given priority class and retry count. */
	protected transient RequestClient client;

	/** What is our priority class? */
	public short getPriorityClass() {
		return priorityClass;
	}

	/** Required because we implement {@link Serializable}. */
	protected ClientRequester() {
		realTimeFlag = false;
		creationTime = 0;
		hashCode = 0;
	}

	protected ClientRequester(short priorityClass, RequestClient requestClient) {
		this.priorityClass = priorityClass;
		this.client = requestClient;
		this.realTimeFlag = client.realTimeFlag();
		if(client == null)
			throw new NullPointerException();
		hashCode = super.hashCode(); // the old object id will do fine, as long as we ensure it doesn't change!
		synchronized(allRequesters) {
			if(!persistent())
				allRequesters.put(this, dumbValue);
		}
		creationTime = System.currentTimeMillis();
	}

	/** Cancel the request. Inner method, subclasses should actually tell 
	 * the ClientGetState or whatever to cancel itself: this does not do 
	 * anything apart from set a flag!
	 * @return Whether we were already cancelled.
	 */
	protected synchronized boolean cancel() {
		boolean ret = cancelled;
		cancelled = true;
		return ret;
	}

	/** Cancel the request. Subclasses must implement to actually tell the
	 * ClientGetState's or ClientPutState's to cancel.
	 * @param context The ClientContext object including essential but 
	 * non-persistent objects such as the schedulers.
	 */
	public abstract void cancel(ClientContext context);

	/** Is the request or insert cancelled? */
	public boolean isCancelled() {
		return cancelled;
	}

	/** Get the URI for the request or insert. For a request this is set at
	 * creation, but for an insert, it is set when we know what the final 
	 * URI will be. */
	public abstract FreenetURI getURI();

	/** Is the request or insert completed (succeeded, failed, or 
	 * cancelled, which is a kind of failure)? */
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
    /**
     * ATTENTION: This may be null for very old databases.
     * @see #getLatestSuccess() Explanation of the content and especially the default value. */
    protected Date latestSuccess = CurrentTimeUTC.get();
	/** Number of blocks which have failed. */
	protected int failedBlocks;
	/** Number of blocks which have failed fatally. */
	protected int fatallyFailedBlocks;
    /** @see #getLatestFailure() */
    protected Date latestFailure = null;
	/** Minimum number of blocks required to succeed for success. */
	protected int minSuccessBlocks;
	/** Has totalBlocks stopped growing? */
	protected boolean blockSetFinalized;
	/** Has at least one block been scheduled to be sent to the network? 
	 * Requests can be satisfied entirely from the datastore sometimes. */
	protected boolean sentToNetwork;
	
    public int getTotalBlocks() {
        return totalBlocks;
    }

    /**
     * UTC Date of latest increase of {@link #successfulBlocks}.<br>
     * Initialized to current time for usability purposes: This allows the user to sort downloads by
     * last success in the user interface to determine which ones are stalling - those will be the
     * ones with the oldest last success date. If we initialized it to "null" only, that would not
     * be possible: The user couldn't distinguish very old stalling downloads from downloads which
     * merely had no success yet because they were added a short time ago.<br> */
    public Date getLatestSuccess() {
        // clone() because Date is mutable.
        // Null-check for backwards compatibility: Old serialized versions of objects of this
        // class might not have this field yet.
        return latestSuccess != null ? (Date)latestSuccess.clone() : new Date(0);
    }

    /**
     * UTC Date of latest increase of {@link #failedBlocks} or {@link #fatallyFailedBlocks}.<br>
     * Null if there was no failure yet. */
    public Date getLatestFailure() {
        // clone() because Date is mutable.
        // Null-check for backwards compatibility: Old serialized versions of objects of this
        // class might not have this field yet.
        return latestFailure != null ? (Date)latestFailure.clone() : null;
    }

	protected synchronized void resetBlocks() {
		totalBlocks = 0;
		successfulBlocks = 0;
        // See ClientRequester.getLatestSuccess() for why this defaults to current time.
        latestSuccess = CurrentTimeUTC.get();
		failedBlocks = 0;
		fatallyFailedBlocks = 0;
        latestFailure = null;
		minSuccessBlocks = 0;
		blockSetFinalized = false;
		sentToNetwork = false;
	}
	
	/** The set of blocks has been finalised, total will not change any
	 * more. Notify clients.
	 * @param context The ClientContext object including essential but 
	 * non-persistent objects such as the schedulers.
	 */
	public void blockSetFinalized(ClientContext context) {
		synchronized(this) {
			if(blockSetFinalized) return;
			blockSetFinalized = true;
		}
		if(logMINOR)
			Logger.minor(this, "Finalized set of blocks for "+this, new Exception("debug"));
		notifyClients(context);
	}

	/** Add a block to our estimate of the total. Don't notify clients. */
	public void addBlock() {
		boolean wasFinalized;
		synchronized (this) {
			totalBlocks++;
			wasFinalized = blockSetFinalized;
		}

		if (wasFinalized) {
			if (LogLevel.MINOR.matchesThreshold(Logger.globalGetThresholdNew()))
				Logger.error(this, "addBlock() but set finalized! on " + this, new Exception("error"));
			else
				Logger.error(this, "addBlock() but set finalized! on " + this);
		}
		
		if(logMINOR) Logger.minor(this, "addBlock(): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks);
	}

	/** Add several blocks to our estimate of the total. Don't notify clients. */
	public void addBlocks(int num) {
		boolean wasFinalized;
		synchronized (this) {
			totalBlocks += num;
			wasFinalized = blockSetFinalized;
		}

		if (wasFinalized) {
			if (LogLevel.MINOR.matchesThreshold(Logger.globalGetThresholdNew()))
				Logger.error(this, "addBlocks() but set finalized! on "+this, new Exception("error"));
			else
				Logger.error(this, "addBlocks() but set finalized! on "+this);
		}
		
		if(logMINOR) Logger.minor(this, "addBlocks("+num+"): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks); 
	}

	/** We completed a block. Count it and notify clients unless dontNotify. */
	public void completedBlock(boolean dontNotify, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Completed block ("+dontNotify+ "): total="+totalBlocks+" success="+successfulBlocks+" failed="+failedBlocks+" fatally="+fatallyFailedBlocks+" finalised="+blockSetFinalized+" required="+minSuccessBlocks+" on "+this);
		synchronized(this) {
			if(cancelled) return;
			successfulBlocks++;
            latestSuccess = CurrentTimeUTC.get();
		}
		if(dontNotify) return;
		notifyClients(context);
	}
	
	transient static final UserAlert brokenClientAlert = new SimpleUserAlert(true, "Some broken downloads/uploads were cancelled. Please restart them.", "Some downloads/uploads were broken due to a bug (some time before 1287) causing unrecoverable database corruption. They have been cancelled. Please restart them from the Downloads or Uploads page.", "Some downloads/uploads were broken due to a pre-1287 bug, please restart them.", UserAlert.ERROR);

    /** A block failed. Count it and notify our clients. */
    public void failedBlock(boolean dontNotify, ClientContext context) {
        synchronized(this) {
            failedBlocks++;
            latestFailure = CurrentTimeUTC.get();
        }
        if(!dontNotify)
            notifyClients(context);
    }

	/** A block failed. Count it and notify our clients. */
	public void failedBlock(ClientContext context) {
	    failedBlock(false, context);
	}

	/** A block failed fatally. Count it and notify our clients. */
	public void fatallyFailedBlock(ClientContext context) {
		synchronized(this) {
			fatallyFailedBlocks++;
            latestFailure = CurrentTimeUTC.get();
		}
		notifyClients(context);
	}

	/** Add one or more blocks to the number of requires blocks, and don't notify the clients. */
	public synchronized void addMustSucceedBlocks(int blocks) {
		totalBlocks += blocks;
		minSuccessBlocks += blocks;
		if(logMINOR) Logger.minor(this, "addMustSucceedBlocks("+blocks+"): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks); 
	}

	/** Insertors should override this. The method is duplicated rather than calling addMustSucceedBlocks to avoid confusing consequences when addMustSucceedBlocks does other things. */
	public synchronized void addRedundantBlocksInsert(int blocks) {
		totalBlocks += blocks;
		minSuccessBlocks += blocks;
		if(logMINOR) Logger.minor(this, "addMustSucceedBlocks("+blocks+"): total="+totalBlocks+" successful="+successfulBlocks+" failed="+failedBlocks+" required="+minSuccessBlocks); 
	}
	
	/** Notify clients by calling innerNotifyClients off-thread. */
	public final void notifyClients(ClientContext context) {
	    context.getJobRunner(persistent()).queueNormalOrDrop(new PersistentJob() {

            @Override
            public boolean run(ClientContext context) {
                innerNotifyClients(context);
                return false;
            }
	        
	    });
	}
	
	/** Notify clients, usually via a SplitfileProgressEvent, of the current progress. Called 
	 * off-thread. Please do not change SimpleEventProducer to always produce events off-thread, it
	 * is better to deal with that here, because events could be re-ordered, which matters for some
	 * events notably SimpleProgressEvent. */
	protected abstract void innerNotifyClients(ClientContext context);
	
	/** Called when we first send a request to the network. Ensures that it really is the first time and
	 * passes on to innerToNetwork().
	 */
	public void toNetwork(ClientContext context) {
		synchronized(this) {
			if(sentToNetwork) return;
			sentToNetwork = true;
		}
		innerToNetwork(context);
	}

	/** Notify clients that a request has gone to the network, for the first time, i.e. we have finished
	 * checking the datastore for at least one part of the request. */
	protected abstract void innerToNetwork(ClientContext context);

	protected void clearCountersOnRestart() {
		this.blockSetFinalized = false;
		this.cancelled = false;
		this.failedBlocks = 0;
		this.fatallyFailedBlocks = 0;
        this.latestFailure = null;
		this.minSuccessBlocks = 0;
		this.sentToNetwork = false;
		this.successfulBlocks = 0;
        // See ClientRequester.getLatestSuccess() for why this defaults to current time.
        this.latestSuccess = CurrentTimeUTC.get();
		this.totalBlocks = 0;
	}

	/** Get client context object */
	public RequestClient getClient() {
		return client;
	}

	/** Change the priority class of the request (request includes inserts here).
	 * @param newPriorityClass The new priority class for the request or insert.
	 * @param ctx The ClientContext, contains essential transient objects such as the schedulers.
	 */
	public void setPriorityClass(short newPriorityClass, ClientContext ctx) {
		short oldPrio;
		synchronized(this) {
			oldPrio = priorityClass;
			this.priorityClass = newPriorityClass;
		}
		if(logMINOR) Logger.minor(this, "Changing priority class of "+this+" from "+oldPrio+" to "+newPriorityClass);
		ctx.getChkFetchScheduler(realTimeFlag).reregisterAll(this, oldPrio);
		ctx.getChkInsertScheduler(realTimeFlag).reregisterAll(this, oldPrio);
		ctx.getSskFetchScheduler(realTimeFlag).reregisterAll(this, oldPrio);
		ctx.getSskInsertScheduler(realTimeFlag).reregisterAll(this, oldPrio);
	}

	public boolean realTimeFlag() {
		return realTimeFlag;
	}

	/** Is this request persistent? */
	public boolean persistent() {
		return client.persistent();
	}

	private static WeakHashMap<ClientRequester,Object> allRequesters = new WeakHashMap<ClientRequester,Object>();
	private static Object dumbValue = new Object();
	public final long creationTime;

	public static ClientRequester[] getAll() {
		synchronized(allRequesters) {
			return allRequesters.keySet().toArray(new ClientRequester[0]);
		}
	}

    /** @return A byte[] representing the original client, to be written to the file storing a 
     * persistent download. E.g. for FCP, this will include the Identifier, whether it is on the 
     * global queue and the client name. 
     * @param checker Used to checksum and isolate large components where we can recover if they 
     * fail.
     * @throws IOException */
    public byte[] getClientDetail(ChecksumChecker checker) throws IOException {
        return new byte[0];
    }
    
    protected static byte[] getClientDetail(PersistentClientCallback callback, ChecksumChecker checker) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        callback.getClientDetail(dos, checker);
        return baos.toByteArray();
    }
    
    private transient boolean resumed = false;
    
    /** Called for a persistent request after startup. Should call notifyClients() at the end,
     * after the callback has been registered etc. 
     * @throws ResumeFailedException */
    public final void onResume(ClientContext context) throws ResumeFailedException {
        synchronized(this) {
            if(resumed) return;
            resumed = true;
        }
        innerOnResume(context);
    }

    /** Called by onResume() once and only once after restarting. Must be overridden, and must call
     * super.innerOnResume(). 
     * @throws ResumeFailedException */
    protected void innerOnResume(ClientContext context) throws ResumeFailedException {
        ClientBaseCallback cb = getCallback();
        client = cb.getRequestClient();
        assert(client.persistent());
        if(sentToNetwork)
            innerToNetwork(context);
    }

    protected abstract ClientBaseCallback getCallback();

    /** Called just before the final write when shutting down the node. */
    public void onShutdown(ClientContext context) {
        // Do nothing.
    }

    public boolean isCurrentState(ClientGetState state) {
        return false;
    }

    /**
     * Get the group the request belongs to. For single requests (the default) this is the request
     * itself; for those in a group, such as a site insert, it is a common value between them.
     */
    public ClientRequestSchedulerGroup getSchedulerGroup() {
      return this;
    }

}
