package freenet.support;

import java.util.Collection;
import java.util.Iterator;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;

public abstract class TransferThread implements Runnable {
	
	private final Executor mExecutor;
	
	private Thread mThread;
	
	private volatile boolean isRunning = false;
	
	private final Collection<ClientGetter> mFetches = getFetchStorage();
	private final Collection<BaseClientPutter> mInserts = getInsertStorage();
	
	public TransferThread(Executor myExecutor, String myName) {
		mExecutor = myExecutor;
		mExecutor.execute(this, myName);
	}

	public void run() {
		isRunning = true;
		mThread = Thread.currentThread();
		
		try {
			Thread.sleep(getStartupDelay());
		} catch (InterruptedException e) {
			mThread.interrupt();
		}
		
		while(isRunning) {
			Thread.interrupted();
			
			try {
				iterate();
				Thread.sleep(getSleepTime());
			}
			catch(InterruptedException e) {
				mThread.interrupt();
			}
		}
		
		abortAllTransfers();
	}
	
	protected void abortAllTransfers() {
		Logger.debug(this, "Trying to stop all requests & inserts");
		
		synchronized(mFetches) {
			Iterator<ClientGetter> r = mFetches.iterator();
			int rcounter = 0;
			while (r.hasNext()) { r.next().cancel(); r.remove(); ++rcounter; }
			Logger.debug(this, "Stopped " + rcounter + " current requests");
		}

		synchronized(mInserts) {
			Iterator<BaseClientPutter> i = mInserts.iterator();
			int icounter = 0;
			while (i.hasNext()) { i.next().cancel(); i.remove(); ++icounter; }
			Logger.debug(this, "Stopped " + icounter + " current inserts");
		}
	}
	
	protected void removeFetch(ClientGetter g) {
		synchronized(mFetches) {
			//g.cancel(); /* FIXME: is this necessary ? */
			mFetches.remove(g);
		}
		Logger.debug(this, "Removed request for " + g.getURI());
	}
	
	protected void removeInsert(BaseClientPutter p) {
		synchronized(mInserts) {
			//p.cancel(); /* FIXME: is this necessary ? */
			mInserts.remove(p);
		}
		Logger.debug(this, "Removed insert for " + p.getURI());
	}
	
	public void terminate() {
		isRunning = false;
		mThread.interrupt();
		try {
			mThread.join();
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
	
	
	public abstract Collection<ClientGetter> getFetchStorage();
	
	public abstract Collection<BaseClientPutter> getInsertStorage();
	
	public abstract long getStartupDelay();

	public abstract long getSleepTime();
	
	public abstract void iterate();

	
	/* Fetches */
	
	public abstract void onSuccess(FetchResult result, ClientGetter state);

	public abstract void onFailure(FetchException e, ClientGetter state);

	/* Inserts */
	
	public abstract void onSuccess(BaseClientPutter state);

	public abstract void onFailure(InsertException e, BaseClientPutter state);

	public abstract void onFetchable(BaseClientPutter state);

	public abstract void onGeneratedURI(FreenetURI uri, BaseClientPutter state);

	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public abstract void onMajorProgress();

}