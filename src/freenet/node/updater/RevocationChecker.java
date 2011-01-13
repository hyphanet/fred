package freenet.node.updater;

import java.io.File;
import java.io.IOException;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.DatabaseDisabledException;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;

/**
 * Fetches the revocation key. Each time it starts, it will try to fetch it until it has 3 DNFs. If it ever finds it, it will
 * be immediately fed to the NodeUpdateManager.
 */
public class RevocationChecker implements ClientGetCallback, RequestClient {

	public final static int REVOCATION_DNF_MIN = 3;
	
	private boolean logMINOR;

	private NodeUpdateManager manager;
	private NodeClientCore core;
	private int revocationDNFCounter;
	private FetchContext ctxRevocation;
	private ClientGetter revocationGetter;
	private boolean wasAggressive;
	/** Last time at which we got 3 DNFs on the revocation key */
	private long lastSucceeded;
	// Kept separately from NodeUpdateManager.hasBeenBlown because there are local problems that can blow the key.
	private volatile boolean blown;
	
	private File blobFile;
	private File tmpBlobFile;

	public RevocationChecker(NodeUpdateManager manager, File blobFile) {
		this.manager = manager;
		core = manager.node.clientCore;
		this.revocationDNFCounter = 0;
		this.blobFile = blobFile;
		this.logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		ctxRevocation = core.makeClient((short)0, true).getFetchContext();
		ctxRevocation.allowSplitfiles = false;
		ctxRevocation.maxArchiveLevels = 1;
		// big enough ?
		ctxRevocation.maxOutputLength = NodeUpdateManager.MAX_REVOCATION_KEY_LENGTH;
		ctxRevocation.maxTempLength = NodeUpdateManager.MAX_REVOCATION_KEY_TEMP_LENGTH;
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
	 * @return True if the checker was already running and the counter was not reset.
	 * */
	public boolean start(boolean aggressive, boolean reset) {
		
		if(manager.isBlown()) {
			Logger.error(this, "Not starting revocation checker: key already blown!");
			return false;
		}
		boolean wasRunning = false;
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		try {
			ClientGetter cg = null;
			ClientGetter toCancel = null;
			synchronized(this) {
				if(aggressive && !wasAggressive) {
					// Ignore old one.
					toCancel = revocationGetter;
					if(logMINOR) Logger.minor(this, "Ignoring old request, because was low priority");
					revocationGetter = null;
					if(toCancel != null) wasRunning = true;
				}
				wasAggressive = aggressive;
				if(revocationGetter != null && 
						!(revocationGetter.isCancelled() || revocationGetter.isFinished()))  {
					if(logMINOR) Logger.minor(this, "Not queueing another revocation fetcher yet, old one still running");
					reset = false;
					wasRunning = false;
				} else {
					if(reset) {
						if(logMINOR) Logger.minor(this, "Resetting DNF count from "+revocationDNFCounter, new Exception("debug"));
						revocationDNFCounter = 0;
					} else {
						if(logMINOR) Logger.minor(this, "Revocation count "+revocationDNFCounter);
					}
					if(logMINOR) Logger.minor(this, "fetcher="+revocationGetter);
					if(revocationGetter != null && logMINOR) Logger.minor(this, "revocation fetcher: cancelled="+revocationGetter.isCancelled()+", finished="+revocationGetter.isFinished());
					try {
						// Client startup may not have completed yet.
						manager.node.clientCore.getPersistentTempDir().mkdirs();
						tmpBlobFile = File.createTempFile("revocation-", ".fblob.tmp", manager.node.clientCore.getPersistentTempDir());
					} catch (IOException e) {
						Logger.error(this, "Cannot record revocation fetch (therefore cannot pass it on to peers)!: "+e+" for "+tmpBlobFile+" dir "+manager.node.clientCore.getPersistentTempDir()+" exists = "+manager.node.clientCore.getPersistentTempDir().exists(), e);
					}
					cg = revocationGetter = new ClientGetter(this, 
							manager.revocationURI, ctxRevocation, 
							aggressive ? RequestStarter.MAXIMUM_PRIORITY_CLASS : RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
							this, null, tmpBlobFile == null ? null : new FileBucket(tmpBlobFile, false, false, false, false, false));
					if(logMINOR) Logger.minor(this, "Queued another revocation fetcher (count="+revocationDNFCounter+")");
				}
			}
			if(toCancel != null)
				toCancel.cancel(null, core.clientContext);
			if(cg != null) {
				core.clientContext.start(cg);
				if(logMINOR) Logger.minor(this, "Started revocation fetcher");
			}
			return wasRunning;
		} catch (FetchException e) {
			Logger.error(this, "Not able to start the revocation fetcher.");
			manager.blow("Cannot start fetch for the auto-update revocation key", true);
			return false;
		} catch (DatabaseDisabledException e) {
			// Impossible
			return false;
		}
	}

	long lastSucceeded() {
		return lastSucceeded;
	}
	
	long lastSucceededDelta() {
		if(lastSucceeded <= 0) return -1;
		return System.currentTimeMillis() - lastSucceeded;
	}
	
	/** Called when the revocation URI changes. */
	public void onChangeRevocationURI() {
		kill();
		start(wasAggressive);
	}

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		onSuccess(result, state, tmpBlobFile);
	}
	
	void onSuccess(FetchResult result, ClientGetter state, File blob) {
		// The key has been blown !
		// FIXME: maybe we need a bigger warning message.
		blown = true;
		moveBlob(blob);
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
		manager.blow(msg, false); // Real one, even if we can't extract the message.
	}
	
	public boolean hasBlown() {
		return blown;
	}

	private void moveBlob(File tmpBlobFile) {
		if(tmpBlobFile == null) {
			Logger.error(this, "No temporary binary blob file moving it: may not be able to propagate revocation, bug???");
			return;
		}
                FileUtil.renameTo(tmpBlobFile, blobFile);
	}

	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		onFailure(e, state, tmpBlobFile);
	}
	
	void onFailure(FetchException e, ClientGetter state, File tmpBlobFile) {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if(logMINOR) Logger.minor(this, "Revocation fetch failed: "+e);
		int errorCode = e.getMode();
		boolean completed = false;
		long now = System.currentTimeMillis();
		if(errorCode == FetchException.CANCELLED) {
			if(tmpBlobFile != null) tmpBlobFile.delete();
			return; // cancelled by us above, or killed; either way irrelevant and doesn't need to be restarted
		}
		if(e.isFatal()) {
			manager.blow("Permanent error fetching revocation (error inserting the revocation key?): "+e.toString(), true);
			moveBlob(tmpBlobFile); // other peers need to know,
			return;
		}
		if(tmpBlobFile != null) tmpBlobFile.delete();
		if(e.newURI != null) {
			manager.blow("Revocation URI redirecting to "+e.newURI+" - maybe you set the revocation URI to the update URI?", false);
		}
		synchronized(this) {
			if(errorCode == FetchException.DATA_NOT_FOUND){
				revocationDNFCounter++;
				if(logMINOR) Logger.minor(this, "Incremented DNF counter to "+revocationDNFCounter);
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
	
	public void onMajorProgress(ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}

	public void kill() {
		if(revocationGetter != null)
			revocationGetter.cancel(null, core.clientContext);
	}

	public long getBlobSize() {
		return blobFile.length();
	}

	/** Get the binary blob, if we have fetched it. */
	public File getBlobFile() {
		if(!manager.isBlown()) return null;
		if(blobFile.exists()) return blobFile;
		return null;
	}

	public boolean persistent() {
		return false;
	}

	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	public boolean realTimeFlag() {
		return false;
	}

}
