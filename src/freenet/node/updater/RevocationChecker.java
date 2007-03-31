package freenet.node.updater;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetchContext;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.support.Logger;

/**
 * Fetches the revocation key. Each time it starts, it will try to fetch it until it has 3 DNFs. If it ever finds it, it will
 * be immediately fed to the NodeUpdaterManager.
 */
public class RevocationChecker implements ClientCallback {

	public final static int REVOCATION_DNF_MIN = 3;
	
	private boolean logMINOR;

	private NodeUpdaterManager manager;
	private NodeClientCore core;
	private int revocationDNFCounter;
	private FetchContext ctxRevocation;
	private ClientGetter revocationGetter;
	private boolean wasAggressive;
	/** Last time at which we got 3 DNFs on the revocation key */
	private long lastSucceeded;

	public RevocationChecker(NodeUpdaterManager manager) {
		this.manager = manager;
		core = manager.node.clientCore;
		this.revocationDNFCounter = 0;
		this.logMINOR = Logger.shouldLog(Logger.MINOR, this);
		ctxRevocation = core.makeClient((short)0, true).getFetcherContext();
		ctxRevocation.allowSplitfiles = false;
		ctxRevocation.cacheLocalRequests = false;
		ctxRevocation.maxArchiveLevels = 1;
		// big enough ?
		ctxRevocation.maxOutputLength = 4096;
		ctxRevocation.maxTempLength = 4096;
		ctxRevocation.maxSplitfileBlockRetries = -1; // if we find content, try forever to get it; not used because of the above size limits.
		ctxRevocation.maxNonSplitfileRetries = 0; // but return quickly normally
	}
	
	public int getRevocationDNFCounter() {
		return revocationDNFCounter;
	}

	public void start(boolean aggressive) {
		start(aggressive, true);
	}
	
	/** Start a fetch.
	 * @param aggressive If set to true, then we have just fetched an update, and therefore can increase the priority of the
	 * fetch to maximum.
	 * */
	public void start(boolean aggressive, boolean reset) {
		
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		try {
			ClientGetter cg = null;
			ClientGetter toCancel = null;
			synchronized(this) {
				if(aggressive && !wasAggressive) {
					// Ignore old one.
					toCancel = revocationGetter;
					if(logMINOR) Logger.minor(this, "Ignoring old request, because was low priority");
					revocationGetter = null;
				}
				wasAggressive = aggressive;
				if(revocationGetter != null && 
						!(revocationGetter.isCancelled() || revocationGetter.isFinished()))  {
					if(logMINOR) Logger.minor(this, "Not queueing another revocation fetcher yet, old one still running");
				} else {
					if(reset)
						revocationDNFCounter = 0;
					if(logMINOR) Logger.minor(this, "fetcher="+revocationGetter);
					if(revocationGetter != null)
						Logger.minor(this, "revocation fetcher: cancelled="+revocationGetter.isCancelled()+", finished="+revocationGetter.isFinished());
					cg = revocationGetter = new ClientGetter(this, core.requestStarters.chkFetchScheduler, 
							core.requestStarters.sskFetchScheduler, manager.revocationURI, ctxRevocation, 
							aggressive ? RequestStarter.MAXIMUM_PRIORITY_CLASS : RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
							this, null);
					if(logMINOR) Logger.minor(this, "Queued another revocation fetcher");
				}
			}
			if(toCancel != null)
				toCancel.cancel();
			if(cg != null) {
				cg.start();
				if(logMINOR) Logger.minor(this, "Started revocation fetcher");
			}
		} catch (FetchException e) {
			Logger.error(this, "Not able to start the revocation fetcher.");
			manager.blow("Cannot fetch the auto-update URI");
		}
	}

	long lastSucceeded() {
		return lastSucceeded;
	}
	
	/** Called when the revocation URI changes. */
	public void onChangeRevocationURI() {
		kill();
		start(wasAggressive);
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		// The key has been blown !
		// FIXME: maybe we need a bigger warning message.
		String msg = null;
		try {
			byte[] buf = result.asByteArray();
			msg = new String(buf);
		} catch (Throwable t) {
			try {
				msg = "Failed to extract result when key blown: "+t;
				Logger.error(this, msg, t);
				System.err.println(msg);
				t.printStackTrace();
			} catch (Throwable t1) {
				msg = "Internal error after retreiving revocation key";
			}
		}
		manager.blow(msg);
	}

	public void onFailure(FetchException e, ClientGetter state) {
		Logger.minor(this, "Revocation fetch failed: "+e);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		int errorCode = e.getMode();
		boolean completed = false;
		long now = System.currentTimeMillis();
		if(errorCode == FetchException.CANCELLED) return; // cancelled by us above, or killed; either way irrelevant and doesn't need to be restarted
		if(e.isFatal()) {
			manager.blow("Permanent error fetching revocation (error inserting the revocation key?): "+e.toString());
			return;
		}
		if(e.newURI != null) {
			manager.blow("Revocation URI redirecting to "+e.newURI+" - maybe you set the revocation URI to the update URI?");
		}
		synchronized(this) {
			if(errorCode == FetchException.DATA_NOT_FOUND){
				revocationDNFCounter++;
				Logger.minor(this, "Incremented DNF counter to "+revocationDNFCounter);
			}
			if(revocationDNFCounter >= 3) {
				lastSucceeded = now;
				completed = true;
				revocationDNFCounter = 0;
			}
			revocationGetter = null;
		}
		if(completed)
			manager.noRevocationFound();
		else
			start(wasAggressive, false);
	}

	public void onSuccess(BaseClientPutter state) {
		// TODO Auto-generated method stub
		
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		// TODO Auto-generated method stub
		
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		// TODO Auto-generated method stub
		
	}

	public void onMajorProgress() {
		// TODO Auto-generated method stub
		
	}

	public void onFetchable(BaseClientPutter state) {
		// TODO Auto-generated method stub
		
	}

	public void kill() {
		if(revocationGetter != null)
			revocationGetter.cancel();
	}

}
