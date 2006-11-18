package freenet.node.updater;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.USKCallback;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.node.Ticker;
import freenet.node.Version;
import freenet.support.Logger;
import freenet.support.io.ArrayBucket;

public class NodeUpdater implements ClientCallback, USKCallback {
	static private boolean logMINOR;
	private FetcherContext ctx;
	private FetchResult result;
	private ClientGetter cg;
	private boolean finalCheck;
	private final FreenetURI URI;
	private final Ticker ticker;
	public final NodeClientCore core;
	private final Node node;
	public final NodeUpdaterManager manager;
	
	private final int currentVersion;
	private int availableVersion;
	private int fetchingVersion;
	private int fetchedVersion;
	private int writtenVersion;
	
	private boolean isRunning;
	private boolean isFetching;
	
	public final boolean extUpdate;
	
	NodeUpdater(NodeUpdaterManager manager, FreenetURI URI, boolean extUpdate, int current) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.manager = manager;
		this.node = manager.node;
		this.URI = URI.setSuggestedEdition(Version.buildNumber()+1);
		this.ticker = node.ps;
		this.core = node.clientCore;
		this.currentVersion = current;
		this.availableVersion = current;
		this.isRunning = true;
		this.cg = null;
		this.isFetching = false;
		this.extUpdate = extUpdate;
		
		FetcherContext tempContext = core.makeClient((short)0, true).getFetcherContext();		
		tempContext.allowSplitfiles = true;
		tempContext.dontEnterImplicitArchives = false;
		this.ctx = tempContext;
		
	}

	void start() {
		try{
			// start at next version, not interested in this version
			USK myUsk=USK.create(URI.setSuggestedEdition(currentVersion+1));
			ctx.uskManager.subscribe(myUsk, this, true, this);
		}catch(MalformedURLException e){
			Logger.error(this,"The auto-update URI isn't valid and can't be used");
			manager.blow("The auto-update URI isn't valid and can't be used");
		}
	}
	
	public synchronized void onFoundEdition(long l, USK key){
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Found edition "+l);
		System.err.println("Found new "+(extUpdate?"freenet-ext.jar " : "")+"update edition "+l);
		if(!isRunning) return;
		int found = (int)key.suggestedEdition;
		
		if(found > availableVersion){
			Logger.minor(this, "Updating availableVersion from "+availableVersion+" to "+found+" and queueing an update");
			this.availableVersion = found;
			ticker.queueTimedJob(new Runnable() {
				public void run() {
					maybeUpdate();
				}
			}, 60*1000); // leave some time in case we get later editions
			manager.onStartFetching(extUpdate);
		}
	}

	public void maybeUpdate(){
		ClientGetter toStart = null;
		if(!manager.isEnabled()) return;
		if(manager.isBlown()) return;
		synchronized(this) {
			if(logMINOR)
				Logger.minor(this, "maybeUpdate: isFetching="+isFetching+", isRunning="+isRunning+", availableVersion="+availableVersion);
			if(isFetching || (!isRunning)) return;
			if(availableVersion == fetchedVersion) return;
			fetchingVersion = availableVersion;
			
			Logger.normal(this,"Starting the update process ("+availableVersion+ ')');
			System.err.println("Starting the update process: found the update ("+availableVersion+"), now fetching it.");
			// We fetch it
			try{
				if((cg==null)||cg.isCancelled()){
					if(logMINOR) Logger.minor(this, "Scheduling request for "+URI.setSuggestedEdition(availableVersion));
					System.err.println("Starting "+(extUpdate?"freenet-ext.jar ":"")+"fetch for "+availableVersion);
					cg = new ClientGetter(this, core.requestStarters.chkFetchScheduler, core.requestStarters.sskFetchScheduler, 
							URI.setSuggestedEdition(availableVersion), ctx, RequestStarter.UPDATE_PRIORITY_CLASS, 
							this, new ArrayBucket());
					toStart = cg;
				}
				isFetching = true;
			}catch (Exception e) {
				Logger.error(this, "Error while starting the fetching: "+e, e);
				isFetching=false;
			}
		}
		if(toStart != null)
			try {
				toStart.start();
			} catch (FetchException e) {
				Logger.error(this, "Error while starting the fetching: "+e, e);
				synchronized(this) {
					isFetching=false;
				}
			}
	}
	
	private final Object writeJarSync = new Object();
	
	public void writeJarTo(File fNew) throws IOException {
		int fetched;
		synchronized(this) {
			fetched = fetchedVersion;
		}
		synchronized(writeJarSync) {
			ArrayBucket bucket = (ArrayBucket) result.asBucket();
			byte[] data = bucket.toByteArray();
			fNew.delete();
			
			FileOutputStream fos;
			fos = new FileOutputStream(fNew);
			
			fos.write(data);
			fos.flush();
			fos.close();
		}
		synchronized(this) {
			writtenVersion = fetched;
		}
	}
	
	public void onSuccess(FetchResult result, ClientGetter state) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		synchronized(this) {
			if(result == null || result.asBucket() == null || result.asBucket().size() == 0) {
				Logger.error(this, "Cannot update: result either null or empty for "+availableVersion);
				System.err.println("Cannot update: result either null or empty for "+availableVersion);
				// Try again
				if(result == null || result.asBucket() == null || availableVersion > fetchingVersion) {
					node.ps.queueTimedJob(new Runnable() {
						public void run() { maybeUpdate(); }
					}, 0);
				}
				return;
			}
			this.fetchedVersion = fetchingVersion;
			System.out.println("Found "+fetchingVersion);
			Logger.normal(this, "Found a new version! (" + fetchingVersion + ", setting up a new UpdatedVersionAvailableUserAlert");
			this.cg = null;
			if(this.result != null) this.result.asBucket().free();
			this.result = result;
		}
		manager.onDownloadedNewJar(extUpdate);
	}

	public void onFailure(FetchException e, ClientGetter state) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(!isRunning) return;
		int errorCode = e.getMode();
		
		if(logMINOR) Logger.minor(this, "onFailure("+e+ ',' +state+ ')');
		synchronized(this) {
			this.cg = null;
			isFetching=false;
		}
		if((errorCode == FetchException.DATA_NOT_FOUND) ||
				(errorCode == FetchException.ROUTE_NOT_FOUND) ||
				(errorCode == FetchException.PERMANENT_REDIRECT) ||
				(errorCode == FetchException.REJECTED_OVERLOAD) ||
				(errorCode == FetchException.CANCELLED)){
			
			Logger.normal(this, "Rescheduling new request");
			ticker.queueTimedJob(new Runnable() {
				public void run() {
					maybeUpdate();
				}
			}, 0);
			maybeUpdate();
		} else {
			Logger.error(this, "Canceling fetch : "+ e.getMessage());
			System.err.println("Unexpected error fetching update: "+e.getMessage());
			if(e.isFatal()) {
				// Wait for the next version
			} else {
				ticker.queueTimedJob(new Runnable() {
					public void run() {
						maybeUpdate();
					}
				}, 60*60*1000);
			}
		}
	}

	public void onSuccess(BaseClientPutter state) {
		// Impossible
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		// Impossible
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		// Impossible
	}

	public synchronized boolean isRunning(){
		return isRunning;
	}
	
	/** Called before kill(). Don't do anything that will involve taking locks. */
	public void preKill() {
		isRunning = false;
	}
	
	void kill(){
		try{
			ClientGetter c;
			synchronized(this) {
				isRunning = false;
				USK myUsk=USK.create(URI.setSuggestedEdition(currentVersion));
				ctx.uskManager.unsubscribe(myUsk, this,	true);
				c = cg;
				cg = null;
			}
			c.cancel();
		}catch(Exception e){
		}
	}
	
	public FreenetURI getUpdateKey(){
		return URI;
	}
	
	public boolean inFinalCheck() {
		return finalCheck;
	}

	public void onMajorProgress() {
		// Ignore
	}

	public synchronized boolean canUpdateNow() {
		return fetchedVersion > currentVersion;
	}
	
	public void onFetchable(BaseClientPutter state) {
		// Ignore, we don't insert
	}

	/** Called when the fetch URI has changed. No major locks are held by caller. */
	public void onChangeURI() {
		kill();
		maybeUpdate();
	}

	public int getWrittenVersion() {
		return writtenVersion;
	}

	public int getFetchedVersion() {
		return fetchedVersion;
	}

	public boolean isFetching() {
		return availableVersion > fetchedVersion && availableVersion > currentVersion;
	}

	public int fetchingVersion() {
		if(fetchingVersion == 0) return availableVersion;
		else return fetchingVersion;
	}
}
