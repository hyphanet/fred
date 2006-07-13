package freenet.node.updater;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.util.Properties;

import org.tanukisoftware.wrapper.WrapperManager;

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
	private ClientGetter revocationGetter;
	private boolean finalCheck;
	private final FreenetURI URI;
	private final FreenetURI revocationURI;
	private final Node node;
	
	private final int currentVersion;
	private int availableVersion;
	
	private String revocationMessage;
	private boolean hasBeenBlown;
	private int revocationDNFCounter;
	
	private boolean isRunning;
	private boolean isFetching;
	
	public boolean isAutoUpdateAllowed;
	
	private final UpdatedVersionAvailableUserAlert alert;
	private RevocationKeyFoundUserAlert revocationAlert;
	
	public NodeUpdater(Node n, boolean isAutoUpdateAllowed, FreenetURI URI, FreenetURI revocationURI) {
		super();
		this.URI = URI;
		URI.setSuggestedEdition(Version.buildNumber()+1);
		this.revocationURI = revocationURI;
		this.revocationAlert = null;
		this.revocationDNFCounter = 0;
		this.node = n;
		node.nodeUpdater = this;
		this.currentVersion = Version.buildNumber();
		this.availableVersion = currentVersion;
		this.hasBeenBlown = false;
		this.isRunning = true;
		this.isAutoUpdateAllowed = isAutoUpdateAllowed;
		this.cg = null;
		this.isFetching = false;
		
		this.alert= new UpdatedVersionAvailableUserAlert(currentVersion, this);
		alert.isValid(false);
		node.alerts.register(alert);
		
		FetcherContext tempContext = n.makeClient((short)0).getFetcherContext();		
		tempContext.allowSplitfiles = true;
		tempContext.dontEnterImplicitArchives = false;
		this.ctx = tempContext;
		
		ctxRevocation = n.makeClient((short)0).getFetcherContext();
		ctxRevocation.allowSplitfiles = false;
		ctxRevocation.cacheLocalRequests = false;
		ctxRevocation.maxArchiveLevels = 1;
		// big enough ?
		ctxRevocation.maxOutputLength = 4096;
		ctxRevocation.maxTempLength = 4096;
		ctxRevocation.maxSplitfileBlockRetries = -1; // if we find content, try forever to get it.
		ctxRevocation.maxNonSplitfileRetries = 0; // but return quickly normally
		
		try{		
			USK myUsk=USK.create(URI.setSuggestedEdition(currentVersion));
			ctx.uskManager.subscribe(myUsk, this, true);
			ctx.uskManager.startTemporaryBackgroundFetcher(myUsk);
			
		}catch(MalformedURLException e){
			Logger.error(this,"The auto-update URI isn't valid and can't be used");
			blow("The auto-update URI isn't valid and can't be used");
		}
	}
	
	public synchronized void onFoundEdition(long l, USK key){
		Logger.minor(this, "Found edition "+l);
		System.err.println("Found new update edition "+l);
		if(!isRunning) return;
		int found = (int)key.suggestedEdition;
		
		if(found > availableVersion){
			Logger.minor(this, "Updating availableVersion from "+availableVersion+" to "+found+" and queueing an update");
			this.availableVersion = found;
			node.ps.queueTimedJob(new Runnable() {
				public void run() {
					maybeUpdate();
				}
			}, 60*1000); // leave some time in case we get later editions
		}
	}

	public synchronized void maybeUpdate(){
		try{
			Logger.minor(this, "maybeUpdate: isFetching="+isFetching+", isRunning="+isRunning+", isUpdatable="+isUpdatable()+", availableVersion="+availableVersion);
			if(isFetching || (!isRunning) || (!isUpdatable())) return;
		}catch (PrivkeyHasBeenBlownException e){
			// Handled in blow().
			isRunning=false;
			return;
		}


		alert.set(availableVersion,false);
		alert.isValid(true);
		Logger.normal(this,"Starting the update process ("+availableVersion+")");
		System.err.println("Starting the update process: found the update ("+availableVersion+"), now fetching it.");
//		We fetch it
		try{
			if((cg==null)||cg.isCancelled()){
				Logger.minor(this, "Scheduling request for "+URI.setSuggestedEdition(availableVersion));
				cg = new ClientGetter(this, node.chkFetchScheduler, node.sskFetchScheduler, 
						URI.setSuggestedEdition(availableVersion), ctx, RequestStarter.UPDATE_PRIORITY_CLASS, 
						this, new ArrayBucket());
				cg.start();
			}
			isFetching = true;
			queueFetchRevocation(0);
		}catch (Exception e) {
			Logger.error(this, "Error while starting the fetching: "+e, e);
			isFetching=false;
		}
	}
	
	private volatile Object updateSync = new Object();
	
	public synchronized void Update() {
		if(!isRunning) return;
		synchronized(updateSync) {
			innerUpdate();
		}
	}
	
	/** 
	 * We try to update the node :p
	 * Must run on its own thread.
	 */
	private synchronized void innerUpdate(){
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

			File fRunning = new File("freenet-cvs-snapshot.jar");
			File fNew = new File("freenet-cvs-snapshot.jar.new");

			boolean nastyRestart = false;

			if(File.separatorChar == '\\') {
				// Do nothing.
			} else {
				nastyRestart = true;
				Properties p = WrapperManager.getProperties();
				String cp1 = p.getProperty("wrapper.java.classpath.1");
				if(cp1.equals("freenet-cvs-snapshot.jar")) {
					// Cool!
				} else if(cp1.equals("freenet-cvs-snapshot.jar.new")) {
					// Swapped; we are running .new
					File tmp = fRunning;
					fRunning = fNew;
					fNew = tmp;
				} else {
					cp1 = p.getProperty("wrapper.java.classpath.2");
					if(cp1.equals("freenet-cvs-snapshot.jar")) {
						// Cool!
					} else if(cp1.equals("freenet-cvs-snapshot.jar.new")) {
						// Swapped; we are running .new
						File tmp = fRunning;
						fRunning = fNew;
						fNew = tmp;
					} else {					
						Logger.error(this, "Cannot restart on Windows due to non-standard config file!");
						System.err.println("Cannot restart on Windows due to non-standard config file!");
						return;
					}
				}
			}

			fNew.delete();

			FileOutputStream fos = new FileOutputStream(fNew);

			fos.write(data);
			fos.flush();
			fos.close();
			System.out.println("################## File written! "+cg.getURI().getSuggestedEdition()+ " " +fNew.getAbsolutePath());

			if(!nastyRestart) {
				// Easy way.
				if(!fNew.renameTo(fRunning)) {
					fRunning.delete();
					if(!fNew.renameTo(fRunning)) {
						System.err.println("ERROR renaming the file!");
						return;
					}
				}
			} else {
				// Hard way.

				if(!WrapperManager.isControlledByNativeWrapper()) {
					Logger.error(this, "Cannot update because not running under wrapper");
					System.err.println("Cannot update because not running under wrapper");
					return;
				}

				try {

					File oldConfig = new File("wrapper.conf");
					File newConfig = new File("wrapper.conf.new");

					FileInputStream fis = new FileInputStream(oldConfig);
					BufferedInputStream bis = new BufferedInputStream(fis);
					InputStreamReader isr = new InputStreamReader(bis);
					BufferedReader br = new BufferedReader(isr);

					fos = new FileOutputStream(newConfig);
					OutputStreamWriter osw = new OutputStreamWriter(fos);
					BufferedWriter bw = new BufferedWriter(osw);

					String line;
					boolean succeeded = false;
					boolean stillSucceeded = false;
					while((line = br.readLine()) != null) {
						if(line.equals("wrapper.java.classpath.1="+fRunning.getName())) {
							bw.write("wrapper.java.classpath.1="+fNew.getName()+"\r\n");
							succeeded = true;
						} else if(line.equals("wrapper.java.classpath.2="+fRunning.getName())) {
							bw.write("wrapper.java.classpath.2="+fNew.getName()+"\r\n");
							succeeded = true;
						} else {
							if(line.equals("wrapper.restart.reload_configuration=TRUE"))
								stillSucceeded = true;
							bw.write(line+"\r\n");
						}
					}
					bw.close();
					br.close();

					if(!succeeded) {
						System.err.println("Not able to update because of non-standard config");
						Logger.error(this, "Not able to update because of non-standard config");
						return;
					}

					if(!stillSucceeded) {
						System.err.println("Not able to update because of non-standard or old config, add a line saying \"wrapper.restart.reload_configuration=TRUE\"");
						Logger.error(this, "Not able to update because of non-standard or old config, add a line saying \"wrapper.restart.reload_configuration=TRUE\"");
						return;
					}

					if(!newConfig.renameTo(oldConfig)) {
						oldConfig.delete();
						if(!newConfig.renameTo(oldConfig)) {
							System.err.println("Failed to rename over old config: update failed.");
							Logger.error(this, "Failed to rename over old config: update failed.");
							return;
						}
					}

					// New config installed.

				} catch (IOException e) {
					Logger.error(this, "Not able to update because of I/O error: "+e, e);
					System.err.println("Not able to update because of I/O error: "+e);
				}

			}
			if(node.getNodeStarter()!=null) {
				System.err.println("Restarting because of update");
				node.getNodeStarter().restart();
			} else{
				System.out.println("New version has been downloaded: please restart your node!");
				node.exit();
			}
			System.err.println("WTF? Restart returned!?");

		}catch(Exception e){
			Logger.error(this, "Error while updating the node : "+e);
			System.out.println("Exception : "+e);
			e.printStackTrace();
		}
	}
	
	public synchronized void onSuccess(FetchResult result, ClientGetter state) {
		if(!state.getURI().equals(revocationURI)){
			System.out.println("Found "+availableVersion);
			Logger.normal(this, "Found a new version! (" + availableVersion + ", setting up a new UpdatedVersionAviableUserAlert");
			alert.set(availableVersion,true);
			alert.isValid(true);		
			this.cg = state;
			if(this.result != null) this.result.asBucket().free();
			this.result = result;
			if(this.isAutoUpdateAllowed)
				scheduleUpdate();
		}else{
			// The key has been blown !
			// FIXME: maybe we need a bigger warning message.
			String msg = null;
			try {
				byte[] buf = result.asByteArray();
				msg = new String(buf);
			} catch (Throwable t) {
				try {
					Logger.error(this, "Failed to extract result when key blown: "+t, t);
					System.err.println("Failed to extract result when key blown: "+t);
					t.printStackTrace();
					msg = "Failed to extract result when key blown: "+t;
				} catch (Throwable t1) {
					msg = "Internal error after retreiving revocation key";
				}
			}
			blow(msg);
		}
	}

	public synchronized void onFailure(FetchException e, ClientGetter state) {
		int errorCode = e.getMode();
		
		if(!state.getURI().equals(revocationURI)){
			Logger.minor(this, "onFailure("+e+","+state+")");
			this.cg = state;
			isFetching=false;
			cg.cancel();
			if((errorCode == FetchException.DATA_NOT_FOUND) ||
					(errorCode == FetchException.ROUTE_NOT_FOUND) ||
					(errorCode == FetchException.PERMANENT_REDIRECT) ||
					(errorCode == FetchException.REJECTED_OVERLOAD)){
				
				Logger.normal(this, "Rescheduling new request");
				maybeUpdate();
			} else {
				Logger.error(this, "Canceling fetch : "+ e.getMessage());
			}
		}else{
			Logger.minor(this, "Revocation fetch failed: "+e);
			if(errorCode == FetchException.DATA_NOT_FOUND){
				revocationDNFCounter++;
				Logger.minor(this, "Incremented DNF counter to "+revocationDNFCounter);
			}
			if(e.isFatal()) this.blow("Permanent error fetching revocation (invalid data inserted?): "+e.toString());
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
		Logger.minor(this, "Queueing fetch of revocation key in "+delay+"ms", new Exception("debug"));
		node.ps.queueTimedJob(new Runnable() { // maybe a FastRunnable? FIXME

			public void run() {
				try {
					// We've got the data, so speed things up a bit.
					ClientGetter cg;
					synchronized(this) {
						if(revocationGetter != null && 
								!(revocationGetter.isCancelled() || revocationGetter.isFinished()))  {
							Logger.minor(this, "Not queueing another revocation fetcher yet");
							return;
						} else {
							Logger.minor(this, "fetcher="+revocationGetter);
							if(revocationGetter != null)
								Logger.minor(this, "revocation fetcher: cancelled="+revocationGetter.isCancelled()+", finished="+revocationGetter.isFinished());
							cg = revocationGetter = new ClientGetter(NodeUpdater.this, node.chkFetchScheduler, node.sskFetchScheduler, revocationURI, ctxRevocation, RequestStarter.MAXIMUM_PRIORITY_CLASS, NodeUpdater.this, null);
							Logger.minor(this, "Queued another revocation fetcher");
						}
					}
					cg.start();
					Logger.minor(this, "Started revocation fetcher");
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
			
		}, 500); // give it time to get close-together next version
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

	public synchronized boolean isUpdatable() throws PrivkeyHasBeenBlownException{
		if(hasBeenBlown) 
			throw new PrivkeyHasBeenBlownException(revocationMessage);
		else 
			return (currentVersion<availableVersion);
	}
	
	public synchronized boolean isRunning(){
		return isRunning;
	}
	
	protected synchronized void kill(){
		isRunning = false;
		try{
			USK myUsk=USK.create(URI.setSuggestedEdition(currentVersion));
			ctx.uskManager.unsubscribe(myUsk, this,	true);
			cg.cancel();
		}catch(Exception e){
		}
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
	
	protected synchronized void  setAutoupdateAllowed(boolean b) {
		this.isAutoUpdateAllowed = b;
	}
	
	public static NodeUpdater maybeCreate(Node node, Config config) throws Exception {
        SubConfig updaterConfig = new SubConfig("node.updater", config);
         
        updaterConfig.register("enabled", true, 1, false, "Check for, and download new versions",
        		"Should your node automatically check for new versions of Freenet. If yes, new versions will be automatically detected and downloaded, but not necessarily installed.",
        		new UpdaterEnabledCallback(node, config));
        
        boolean enabled = updaterConfig.getBoolean("enabled");

        if(enabled) {
        	// is the auto-update allowed ?
        	updaterConfig.register("autoupdate", false, 2, false, "Automatically install new versions", "Should your node automatically update to the newest version of Freenet, without asking?",
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

	public boolean inFinalCheck() {
		return finalCheck;
	}

	public void onMajorProgress() {
		// Ignore
	}
}
