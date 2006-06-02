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
import freenet.config.Config;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.node.Version;
import freenet.node.useralerts.RevocationKeyFoundUserAlert;
import freenet.node.useralerts.UpdatedVersionAvailableUserAlert;
import freenet.support.ArrayBucket;
import freenet.support.Logger;

public class NodeUpdater implements ClientCallback, USKCallback {
	private FetcherContext ctx;
	private FetcherContext ctxRevocation;
	private FetchResult result;
	private ClientGetter cg;
	private boolean finalCheck = false;
	private final FreenetURI URI;
	private final FreenetURI revocationURI;
	private final Node node;
	
	private final int currentVersion;
	private int availableVersion;
	
	private String revocationMessage;
	private boolean hasBeenBlown;
	private int revocationDNFCounter;
	
	private boolean isRunning = false;
	private boolean isFetching = false;
	
	public final boolean isAutoUpdateAllowed;
	
	private final UpdatedVersionAvailableUserAlert alert;
	private RevocationKeyFoundUserAlert revocationAlert;
	
	// TODO: Start the revocation url fetching!
	public NodeUpdater(Node n, boolean isAutoUpdateAllowed, FreenetURI URI, FreenetURI revocationURI) {
		super();
		this.URI = URI;
		URI.setSuggestedEdition(Version.buildNumber()+1);
		this.revocationURI = revocationURI;
		this.revocationAlert = null;
		this.revocationDNFCounter = 0;
		this.node = n;
		this.currentVersion = Version.buildNumber();
		this.availableVersion = currentVersion;
		this.hasBeenBlown = false;
		this.isRunning = true;
		this.isAutoUpdateAllowed = isAutoUpdateAllowed;
		this.cg = null;
		this.isFetching = false;
		
		this.alert= new UpdatedVersionAvailableUserAlert(currentVersion);
		alert.isValid(false);
		node.alerts.register(alert);
		
		FetcherContext ctx = n.makeClient((short)0).getFetcherContext();		
		ctx.allowSplitfiles = true;
		ctx.dontEnterImplicitArchives = false;
		this.ctx = ctx;
		
		ctxRevocation = n.makeClient((short)0).getFetcherContext();
		ctxRevocation.allowSplitfiles = false;
		ctxRevocation.cacheLocalRequests = false;
		ctxRevocation.followRedirects = false;
		ctxRevocation.maxArchiveLevels = 1;
		// big enough ?
		ctxRevocation.maxOutputLength = 4096;
		ctxRevocation.maxTempLength = 4096;
		ctxRevocation.maxSplitfileBlockRetries = -1; // if we find content, try forever to get it.
		ctxRevocation.maxNonSplitfileRetries = 0; // but return quickly normally
		
		try{		
			USK myUsk=USK.create(URI);
			ctx.uskManager.subscribe(myUsk, this,	true);
			ctx.uskManager.startTemporaryBackgroundFetcher(myUsk);
			
			ClientGetter cg = new ClientGetter(this, node.chkFetchScheduler, node.sskFetchScheduler, revocationURI, ctxRevocation, RequestStarter.UPDATE_PRIORITY_CLASS, this, null);
			cg.start();
			
		}catch(MalformedURLException e){
			Logger.error(this,"The auto-update URI isn't valid and can't be used");
			blow("The auto-update URI isn't valid and can't be used");
		} catch (FetchException e) {
			Logger.error(this, "Not able to start the revocation fetcher.");
			blow("Cannot fetch the auto-update URI");
		}
	}
	
	public synchronized void onFoundEdition(long l, USK key){
		// FIXME : Check if it has been blown
		int found = (int)key.suggestedEdition;
		
		if(found > availableVersion){
			this.availableVersion = found;
			try{
				maybeUpdate();
			}catch (Exception e){
				
			}
			this.isRunning=true;
		}
	}

	public synchronized void maybeUpdate(){
		try{
			if(isFetching || !isRunning || !isUpdatable()) return;
		}catch (PrivkeyHasBeenBlownException e){
			// how to handle it ? a new UserAlert or an imediate exit?
			Logger.error(this, "The auto-updating Private key has been blown!");
			node.exit();
		}
		
		isRunning=false;
		
		//TODO maybe a UpdateInProgress alert ?
		Logger.normal(this,"Starting the update process");
		System.err.println("Starting the update process: found the update, now fetching it.");
//		We fetch it
		try{
			if(cg==null||cg.isCancelled()){
				cg = new ClientGetter(this, node.chkFetchScheduler, node.sskFetchScheduler, 
						URI.setSuggestedEdition(availableVersion), ctx, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, 
						this, new ArrayBucket());
			}
			cg.start();
			isFetching = true;
		}catch (Exception e) {
			Logger.error(this, "Error while starting the fetching");
		}
	}
	
	/** 
	 * We try to update the node :p
	 * Must run on its own thread.
	 */
	public synchronized void Update(){
		Logger.minor(this, "Update() called");
		if((result == null) || hasBeenBlown) {
			Logger.minor(this, "Returning: result="+result+", isAutoUpdateAllowed="+isAutoUpdateAllowed+", hasBeenBlown="+hasBeenBlown);
			return;
		}

		this.revocationDNFCounter = 0;
		this.finalCheck = true;
		
		System.err.println("Searching for revocation key");
		this.queueFetchRevocation(100);
		while(revocationDNFCounter < 3) {
			System.err.println("Revocation counter: "+revocationDNFCounter);
			if(this.hasBeenBlown) {
				Logger.error(this, "The revocation key has been found on the network : blocking auto-update");
				return;
			}
			try {
				wait(100*1000);
			} catch (InterruptedException e) {
				// Ignore
			}
		}

		System.err.println("Update in progress");
		Logger.normal(this, "Update in progress");
		try{
			ArrayBucket bucket = (ArrayBucket) result.asBucket();
			byte[] data = bucket.toByteArray();
			
			File f = new File("freenet-cvs-snapshot.jar.new");
			f.delete();
			
			FileOutputStream fos = new FileOutputStream(f);
			
			fos.write(data);
			fos.flush();
			fos.close();
			System.out.println("################## File written! "+cg.getURI().getSuggestedEdition()+ " " +f.getAbsolutePath());
			
			File f2 = new File("freenet-cvs-snapshot.jar");
			f2.delete();
			
			if(f.renameTo(f2)){
				if(node.getNodeStarter()!=null)
					node.getNodeStarter().restart();
				else{
					System.out.println("New version has been downloaded: please restart your node!");
					node.exit();
				}	
			}else
				System.out.println("ERROR renaming the file!");
			
			
		}catch(Exception e){
			Logger.error(this, "Error while updating the node : "+e);
			System.out.println("Exception : "+e);
			e.printStackTrace();
		}
	}
	
	public void onSuccess(FetchResult result, ClientGetter state) {
		// TODO ensure it works
		if(!state.getURI().equals(revocationURI)){
			System.out.println("Found "+availableVersion);
			Logger.normal(this, "Found a new version! (" + availableVersion + ", setting up a new UpdatedVersionAviableUserAlert");
			alert.set(availableVersion);
			alert.isValid(true);		
			synchronized(this){
				this.cg = state;
				this.result = result;
				if(this.isAutoUpdateAllowed)
					scheduleUpdate();
			}
		}else{
			// The key has been blown !
			// FIXME: maybe we need a bigger warning message.
			try{
				blow(result.asBucket().getOutputStream().toString());
				Logger.error(this, "The revocation key has been found on the network : blocking auto-update");
			}catch(IOException e){
				// We stop anyway.
				synchronized(this){
					this.hasBeenBlown = true;
				}
				Logger.error(this, "Unable to set the revocation flag");
			}
		}
	}

	public synchronized void onFailure(FetchException e, ClientGetter state) {
		int errorCode = e.getMode();
		
		if(!state.getURI().equals(revocationURI)){	
			this.cg = state;
			
			cg.cancel();
			if(errorCode == FetchException.DATA_NOT_FOUND ||
					errorCode == FetchException.ROUTE_NOT_FOUND ||
					errorCode == FetchException.PERMANENT_REDIRECT ||
					errorCode == FetchException.REJECTED_OVERLOAD){
				
				Logger.normal(this, "Rescheduling new request");
				maybeUpdate();
			}else
				Logger.error(this, "Canceling fetch : "+ e.getMessage());
		}else{
			if(errorCode == FetchException.DATA_NOT_FOUND){
				revocationDNFCounter++;
			}
			// Start it again
			if(this.finalCheck) {
				if(revocationDNFCounter < 3)
					queueFetchRevocation(1000);
				else
					notifyAll();
			} else {
				boolean pause = (revocationDNFCounter == 3);
				if(pause) revocationDNFCounter = 0;
				queueFetchRevocation(pause ? 60*60*1000 : 5000);
			}
		}
	}

	private void queueFetchRevocation(long delay) {
		node.ps.queueTimedJob(new Runnable() { // maybe a FastRunnable? FIXME

			public void run() {
				try {
					// We've got the data, so speed things up a bit.
					ClientGetter cg = new ClientGetter(NodeUpdater.this, node.chkFetchScheduler, node.sskFetchScheduler, revocationURI, ctxRevocation, RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, NodeUpdater.this, null);
					cg.start();
				} catch (FetchException e) {
					Logger.error(this, "Not able to start the revocation fetcher.");
					blow("Cannot fetch the auto-update URI");
				}
			}
			
		}, delay);
	}

	private void scheduleUpdate() {
		node.ps.queueTimedJob(new Runnable() { // definitely needs its own thread

			public void run() {
				Update();
			}
			
		}, 50);
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

	public boolean isUpdatable() throws PrivkeyHasBeenBlownException{
		if(hasBeenBlown) 
			throw new PrivkeyHasBeenBlownException(revocationMessage);
		else 
			return (currentVersion<availableVersion);
	}
	
	public boolean isRunning(){
		return isRunning;
	}
	
	public synchronized void blow(String msg){
		if(hasBeenBlown){
			Logger.error(this, "The key has ALREADY been marked as blown!");
		}else{
			this.revocationMessage = msg;
			this.hasBeenBlown = true;
			if(revocationAlert==null){
				revocationAlert = new RevocationKeyFoundUserAlert(msg);
				node.alerts.register(revocationAlert);
				// we don't need to advertize updates : we are not going to do them
				node.alerts.unregister(alert);
			}
			Logger.error(this, "The updater has acknoledged that it knows the private key has been blown");
		}
	}
	
	public FreenetURI getUpdateKey(){
		return URI;
	}
	
	public FreenetURI getRevocationKey(){
		return revocationURI;
	}
	
	public static NodeUpdater maybeCreate(Node node, Config config) throws Exception {
        SubConfig updaterConfig = new SubConfig("node.updater", config);
         
        updaterConfig.register("enabled", true, 1, false, "Enable Node's updater?",
        		"Whether to enable the node's updater. It won't auto-update unless node.updater.autoupdate is true, it will just warn",
        		new UpdaterEnabledCallback(node));
        
        boolean enabled = updaterConfig.getBoolean("enabled");

        if(enabled) {
        	// is the auto-update allowed ?
        	updaterConfig.register("autoupdate", false, 2, false, "Is the node allowed to auto-update?", "Is the node allowed to auto-update?",
        			new AutoUpdateAllowedCallback(node));
        	boolean autoUpdateAllowed = updaterConfig.getBoolean("autoupdate");
        	
        	updaterConfig.register("URI",
        			"freenet:USK@SIDKS6l-eOU8IQqDo03d~3qqBd-69WG60aDgg4nWqss,CPFqYi95Is3GwzAdAKtAuFMCXDZFFWC3~uPoidCD67s,AQABAAE/update/"+Version.buildNumber()+"/",
        			3, true, "Where should the node look for updates?",
        			"Where should the node look for updates?",
        			new UpdateURICallback(node));
        	
        	String URI = updaterConfig.getString("URI");
        	
        	
        	updaterConfig.register("revocationURI",
        			"freenet:SSK@VOfCZVTYPaatJ~eB~4lu2cPrWEmGyt4bfbB1v15Z6qQ,B6EynLhm7QE0se~rMgWWhl7wh3rFWjxJsEUcyohAm8A,AQABAAE/revoked/",
        			3, true, "Where should the node look for revocation ?",
        			"Where should the node look for revocation ?",
        			new UpdateRevocationURICallback(node));
        	
        	String revURI = updaterConfig.getString("revocationURI");
        	
        	
        	updaterConfig.finishedInitialization();
        	try{
        		return new NodeUpdater(node , autoUpdateAllowed, new FreenetURI(URI), new FreenetURI(revURI));
        	}catch(Exception e){
        		Logger.error(node, "Error starting the NodeUpdater: "+e);
        		throw new Exception("Unable to start the NodeUpdater up");
        	}
        } else {
        	updaterConfig.finishedInitialization();
        	return null;
        }
	}
}
