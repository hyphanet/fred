package freenet.node;


import java.io.File;
import java.io.FileOutputStream;
import java.net.MalformedURLException;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.USKCallback;
import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.Node.NodeInitException;
import freenet.support.ArrayBucket;
import freenet.support.Logger;

class PrivkeyHasBeenBlownException extends Exception{	
	private static final long serialVersionUID = -1;
	
	PrivkeyHasBeenBlownException(String msg) {
		super("The project's private key has been blown, meaning that it has been compromized"+
			  "and shouldn't be trusted anymore. Please get a new build by hand and verify CAREFULLY"+
			  "its signature and CRC. Here is the revocation message: "+msg);
	}
}

class UpdaterEnabledCallback implements BooleanCallback {
	
	final Node node;
	
	UpdaterEnabledCallback(Node n) {
		this.node = n;
	}
	
	public boolean get() {
		return node.getNodeUpdater() != null;
	}
	
	public void set(boolean val) throws InvalidConfigValueException {
		if(val == get()) return;
		// FIXME implement
		throw new InvalidConfigValueException("Cannot be updated on the fly");
	}
}

class AutoUpdateAllowedCallback implements BooleanCallback {
	
	final Node node;
	
	AutoUpdateAllowedCallback(Node n) {
		this.node = n;
	}
	
	public boolean get() {
		NodeUpdater nu = node.getNodeUpdater();
		return nu.isAutoUpdateAllowed;
	}
	
	public void set(boolean val) throws InvalidConfigValueException {
		if(val == get()) return;
		// Good idea to prevent it ?
		throw new InvalidConfigValueException("Cannot be updated on the fly for security reasons");
	}
}

class UpdateURICallback implements StringCallback{

	private final Node node;
	private final String baseURI = "freenet:USK@SIDKS6l-eOU8IQqDo03d~3qqBd-69WG60aDgg4nWqss,CPFqYi95Is3GwzAdAKtAuFMCXDZFFWC3~uPoidCD67s,AQABAAE/update/";
			
	public UpdateURICallback(Node node) {
		this.node = node;
	}
	
	public String get() {
		NodeUpdater nu = node.getNodeUpdater();
		if (nu != null)
			return nu.getUpdateKey().toString(true);
		else
			return baseURI+Version.buildNumber()+"/";
	}

	public void set(String val) {
		if(val == get()) return;
		// Good idea to prevent it ? 
		//
		// Maybe it NEEDS to be implemented
		Logger.error(this, "Node's updater URI can't be updated on the fly");
		return;
	}	
}

class UpdaterevocationURICallback implements StringCallback{

	private final Node node;
	private final String baseURI = "SSK@VOfCZVTYPaatJ~eB~4lu2cPrWEmGyt4bfbB1v15Z6qQ,B6EynLhm7QE0se~rMgWWhl7wh3rFWjxJsEUcyohAm8A,AQABAAE/revoked/";
			
	public UpdaterevocationURICallback(Node node) {
		this.node = node;
	}
	
	public String get() {
		NodeUpdater nu = node.getNodeUpdater();
		if (nu != null)
			return nu.getRevocationKey().toString(true);
		else
			return baseURI;
	}

	public void set(String val) {
		if(val == get()) return;
		// Good idea to prevent it ? 
		//
		// Maybe it NEEDS to be implemented
		Logger.error(this, "Node's updater revocationURI can't be updated on the fly");
		return;
	}	
}

class UpdatedVersionAvailableUserAlert implements UserAlert {
	private boolean isValid;
	private int version;

	UpdatedVersionAvailableUserAlert(int version){
		this.version=version;
		isValid=false;
	}
	
	public synchronized void set(int v){
		version = v;
	}
	
	public boolean userCanDismiss() {
		return false;
	}

	public String getTitle() {
		return "A new stable version of Freenet is available";
	}

	public String getText() {
		return "It seems that your node isn't running the latest version of the software. "+
		"Updating to "+version+" is advised.";
	}

	public short getPriorityClass() {
		return UserAlert.MINOR;
	}
	
	public boolean isValid() {
		return isValid;
	}
	
	public void isValid(boolean b){
		isValid=b;
	}
}


public class NodeUpdater implements ClientCallback, USKCallback {
	private FetcherContext ctx;
	private FetchResult result;
	private ClientGetter cg;
	private final FreenetURI URI;
	private final FreenetURI revocationURI;
	private final Node node;
	
	private final int currentVersion;
	private int availableVersion;
	
	private String revocationMessage;
	private boolean hasBeenBlown;
	
	private boolean isRunning = false;
	private boolean isFetching = false;
	
	public final boolean isAutoUpdateAllowed;
	
	private final UpdatedVersionAvailableUserAlert alert;
	
	public NodeUpdater(Node n, boolean isAutoUpdateAllowed, FreenetURI URI, FreenetURI revocationURI) {
		super();
		this.URI = URI;
		this.revocationURI = revocationURI;
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
		
		try{		
			USK myUsk=USK.create(URI);
			ctx.uskManager.subscribe(myUsk, this,	true);
			ctx.uskManager.startTemporaryBackgroundFetcher(myUsk);
		}catch(MalformedURLException e){
			Logger.error(this,"The auto-update URI isn't valid and can't be used");
			this.hasBeenBlown=true;
			this.revocationMessage = new String("The auto-update URI isn't valid and can't be used");
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
			System.out.println("Found "+availableVersion);
			Logger.normal(this, "Found a new version!, setting up a new UpdatedVersionAviableUserAlert");
			alert.set(availableVersion);
			alert.isValid(true);		
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
		if(isAutoUpdateAllowed){
			Logger.normal(this,"Starting the update process");
//			We fetch it
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
		}else{
			Logger.normal(this,"Not starting the update process as it's not allowed");
		}
	}
	
	/** 
	 * We try to update the node :p
	 *
	 */
	public synchronized void Update(){
		if((result == null) || !isAutoUpdateAllowed || hasBeenBlown)
			return;
		
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
			if(f.renameTo(f2)){
				if(node.getNodeStarter()!=null)
					node.getNodeStarter().restart();
				else
					node.exit();
			}else
				System.out.println("ERROR renaming the file!");
			
		}catch(Exception e){
			Logger.error(this, "Error while updating the node : "+e);
			System.out.println("Exception : "+e);
			e.printStackTrace();
		}
	}
	
	public synchronized void onSuccess(FetchResult result, ClientGetter state) {
		this.cg = state;
		this.result = result;
		Update();
	}

	public synchronized void onFailure(FetchException e, ClientGetter state) {
		this.cg = state;
		
		cg.cancel();
		maybeUpdate();
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
		this.revocationMessage = msg;
		this.hasBeenBlown = true;
	}
	
	public FreenetURI getUpdateKey(){
		return URI;
	}
	
	public FreenetURI getRevocationKey(){
		return revocationURI;
	}
	
	public static NodeUpdater maybeCreate(Node node, Config config) throws NodeInitException {
        SubConfig updaterConfig = new SubConfig("node.updater", config);
         
        updaterConfig.register("enabled", false, 1, false, "Enable Node's updater?",
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
        			new UpdaterevocationURICallback(node));
        	
        	String revURI = updaterConfig.getString("revocationURI");
        	
        	
        	updaterConfig.finishedInitialization();
        	try{
        		return new NodeUpdater(node , autoUpdateAllowed, new FreenetURI(URI), new FreenetURI(revURI));
        	}catch(Exception e){
        		Logger.error(node, "Error starting the NodeUpdater: "+e);
        		throw new NodeInitException(Node.EXIT_COULD_NOT_START_UPDATER,"Unable to start the NodeUpdater up");
        	}
        } else {
        	updaterConfig.finishedInitialization();
        	return null;
        }
	}
}
