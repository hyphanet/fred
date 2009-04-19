/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.Collection;
import java.util.Iterator;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.PrioRunnable;
import freenet.support.io.TempBucketFactory;

/**
 * A thread which periodically wakes up and iterates to start fetches and/or inserts.
 * 
 * When calling <code>start()</code>, the thread will iterate the first time after <code>getStartupDelay()</code> milliseconds.
 * After each iteration, it will sleep for <code>getSleepTime()</code> milliseconds.
 * 
 * @author xor
 */
public abstract class TransferThread implements PrioRunnable, ClientCallback {
	
	private final String mName;
	protected final Node mNode;
	protected final HighLevelSimpleClient mClient;
	protected final TempBucketFactory mTBF;
	
	private Thread mThread;
	
	private volatile boolean isRunning = false;
	private volatile boolean shutdownFinished = false;
	
	private final Collection<ClientGetter> mFetches = createFetchStorage();
	private final Collection<BaseClientPutter> mInserts = createInsertStorage();
	
	public TransferThread(Node myNode, HighLevelSimpleClient myClient, String myName) {
		mNode = myNode;
		mClient = myClient;
		mTBF = mNode.clientCore.tempBucketFactory;
		mName = myName;
	}
	
	protected void start() {
		mNode.executor.execute(this, mName);
		Logger.debug(this, this.getClass().getSimpleName() + " started.");
	}
	
	/** Specify the priority of this thread. Priorities to return can be found in class NativeThread. */
	public abstract int getPriority();

	public void run() {
		isRunning = true;
		mThread = Thread.currentThread();
		
		try {
			Thread.sleep(getStartupDelay());
		} catch (InterruptedException e) {
			mThread.interrupt();
		}
		
		try {
			while(isRunning) {
				Thread.interrupted();

				try {
					Logger.debug(this, "Loop running...");
					iterate();
					long sleepTime = getSleepTime();
					Logger.debug(this, "Loop finished. Sleeping for " + (sleepTime/(1000*60)) + " minutes.");
					Thread.sleep(sleepTime);
				}
				catch(InterruptedException e) {
					mThread.interrupt();
				}
				catch(Exception e) {
					Logger.error(this, "Error in iterate probably()", e);
				}
			}
		}
		
		finally {
			try {
				abortAllTransfers();
			}
			catch(RuntimeException e) {
				Logger.error(this, "SHOULD NOT HAPPEN, please report this exception", e);
			}
			finally {
			synchronized (this) {
				shutdownFinished = true;
				notify();
			}
			}
		}
	}
	
	/**
	 * Wakes up the thread so that iterate() is called.
	 */
	public void nextIteration() {
		mThread.interrupt();
	}
	
	protected void abortAllTransfers() {
		Logger.debug(this, "Trying to stop all requests & inserts");
		
		abortFetches();
		abortInserts();
	}
	
	protected void abortFetches() {
		Logger.debug(this, "Trying to stop all fetches");
		if(mFetches != null) synchronized(mFetches) {
			Iterator<ClientGetter> r = mFetches.iterator();
			int rcounter = 0;
			while (r.hasNext()) { r.next().cancel(null, mNode.clientCore.clientContext); r.remove(); ++rcounter; }
			Logger.debug(this, "Stopped " + rcounter + " current requests");
		}
	}
	
	protected void abortInserts() {
		if(mInserts != null) synchronized(mInserts) {
			Iterator<BaseClientPutter> i = mInserts.iterator();
			int icounter = 0;
			while (i.hasNext()) { i.next().cancel(null, mNode.clientCore.clientContext); i.remove(); ++icounter; }
			Logger.debug(this, "Stopped " + icounter + " current inserts");
		}
	}
	
	protected void addFetch(ClientGetter g) {
		synchronized(mFetches) {
			mFetches.add(g);
		}
	}
	
	protected void removeFetch(ClientGetter g) {
		synchronized(mFetches) {
			//g.cancel(); /* FIXME: is this necessary ? */
			mFetches.remove(g);
		}
		Logger.debug(this, "Removed request for " + g.getURI());
	}
	
	protected void addInsert(BaseClientPutter p) {
		synchronized(mInserts) {
			mInserts.add(p);
		}
	}
	
	protected void removeInsert(BaseClientPutter p) {
		synchronized(mInserts) {
			//p.cancel(); /* FIXME: is this necessary ? */
			mInserts.remove(p);
		}
		Logger.debug(this, "Removed insert for " + p.getURI());
	}
	
	protected int fetchCount() {
		synchronized(mFetches) {
			return mFetches.size();
		}
	}
	
	protected int insertCount() {
		synchronized(mInserts) {
			return mInserts.size();
		}
	}
	
	public void terminate() {
		Logger.debug(this, "Terminating...");
		isRunning = false;
		mThread.interrupt();
		
		synchronized(this) {
			while(!shutdownFinished) {
				try {
					wait();
				}
				catch (InterruptedException e) {
					Thread.interrupted();
				}
			}
		}
		Logger.debug(this, "Terminated.");
	}
	
	
	protected abstract Collection<ClientGetter> createFetchStorage();
	
	protected abstract Collection<BaseClientPutter> createInsertStorage();
	
	protected abstract long getStartupDelay();

	protected abstract long getSleepTime();
	
	/**
	 * Called by the TransferThread after getStartupDelay() milliseconds for the first time and then after each getSleepTime() milliseconds.
	 */
	protected abstract void iterate();

	
	/* Fetches */
	
	public abstract void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container);

	public abstract void onFailure(FetchException e, ClientGetter state, ObjectContainer container);

	/* Inserts */
	
	public abstract void onSuccess(BaseClientPutter state, ObjectContainer container);

	public abstract void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container);

	public abstract void onFetchable(BaseClientPutter state, ObjectContainer container);

	public abstract void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container);

	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public abstract void onMajorProgress(ObjectContainer container);

	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing TransferThread in database", new Exception("error"));
		return false;
	}
	
}