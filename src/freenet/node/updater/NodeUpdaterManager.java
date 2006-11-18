package freenet.node.updater;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.NodeStarter;
import freenet.node.Version;
import freenet.node.updater.UpdateDeployContext.UpdateCatastropheException;
import freenet.node.useralerts.RevocationKeyFoundUserAlert;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UpdatedVersionAvailableUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.Logger;

/**
 * Supervises NodeUpdater's. Enables us to easily update multiple files, 
 * change the URI's on the fly, eliminates some messy code in the 
 * callbacks etc.
 */
public class NodeUpdaterManager {

	public final static String UPDATE_URI = "freenet:USK@SIDKS6l-eOU8IQqDo03d~3qqBd-69WG60aDgg4nWqss,CPFqYi95Is3GwzAdAKtAuFMCXDZFFWC3~uPoidCD67s,AQABAAE/update/"+Version.buildNumber();
	public final static String REVOCATION_URI = "freenet:SSK@VOfCZVTYPaatJ~eB~4lu2cPrWEmGyt4bfbB1v15Z6qQ,B6EynLhm7QE0se~rMgWWhl7wh3rFWjxJsEUcyohAm8A,AQABAAE/revoked";
	public final static String EXT_URI = "freenet:USK@SIDKS6l-eOU8IQqDo03d~3qqBd-69WG60aDgg4nWqss,CPFqYi95Is3GwzAdAKtAuFMCXDZFFWC3~uPoidCD67s,AQABAAE/ext/"+NodeStarter.extBuildNumber;
	
	FreenetURI updateURI;
	FreenetURI extURI;
	FreenetURI revocationURI;
	
	NodeUpdater mainUpdater;
	NodeUpdater extUpdater;
	
	boolean wasEnabledOnStartup;
	/** Is auto-update enabled? */
	boolean isAutoUpdateAllowed;
	/** Has the user given the go-ahead? */
	boolean armed;
	/** Should we check for freenet-ext.jar updates? 
	 * Normally set only when our freenet-ext.jar is known to be out of date. */
	final boolean shouldUpdateExt;
	/** Currently deploying an update? */
	boolean isDeployingUpdate;
	
	Node node;
	
	final RevocationChecker revocationChecker;
	private String revocationMessage;
	private boolean hasBeenBlown;
	
	/** Is there a new main jar ready to deploy? */
	private boolean hasNewMainJar;
	/** Is there a new ext jar ready to deploy? */
	private boolean hasNewExtJar;
	/** If another main jar is being fetched, when did the fetch start? */
	private long startedFetchingNextMainJar;
	/** If another ext jar is being fetched, when did the fetch start? */
	private long startedFetchingNextExtJar;

	// Revocation alert
	private RevocationKeyFoundUserAlert revocationAlert;
	// Update alert
	private final UpdatedVersionAvailableUserAlert alert;

	private boolean logMINOR;
	
	public NodeUpdaterManager(Node node, Config config) throws InvalidConfigValueException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.node = node;
		this.hasBeenBlown = false;
		shouldUpdateExt = NodeStarter.extBuildNumber < NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER;
		this.alert= new UpdatedVersionAvailableUserAlert(this);
		alert.isValid(false);
		
        SubConfig updaterConfig = new SubConfig("node.updater", config);
        
        updaterConfig.register("enabled", WrapperManager.isControlledByNativeWrapper(), 1, true, false, "Check for, and download new versions",
        		"Should your node automatically check for new versions of Freenet. If yes, new versions will be automatically detected and downloaded, but not necessarily installed. This setting resets itself always back to false unless the node runs within the wrapper.",
        		new UpdaterEnabledCallback());
        
        wasEnabledOnStartup = updaterConfig.getBoolean("enabled");

        // is the auto-update allowed ?
        updaterConfig.register("autoupdate", false, 2, false, true, "Automatically install new versions", "Should your node automatically update to the newest version of Freenet, without asking?",
        		new AutoUpdateAllowedCallback());
        isAutoUpdateAllowed = updaterConfig.getBoolean("autoupdate");

        updaterConfig.register("URI", UPDATE_URI, 3,
        		true, false, "Where should the node look for updates?",
        		"Where should the node look for updates?",
        		new UpdateURICallback(false));
        
        try {
			updateURI = new FreenetURI(updaterConfig.getString("URI"));
		} catch (MalformedURLException e) {
			throw new InvalidConfigValueException("Invalid updateURI: "+e);
		}

		if(updateURI.lastMetaString() != null && updateURI.lastMetaString().length() == 0) {
			// FIXME remove this hack, put in because of bad default
			System.err.println("Correcting auto-update URI: Removing extra /");
			updateURI = updateURI.popMetaString();
		}
		
        updaterConfig.register("revocationURI",	REVOCATION_URI,4,
        		true, false, "Where should the node look for revocation ?",
        		"Where should the node look for revocation ?",
        		new UpdateRevocationURICallback());
        
        try {
			revocationURI = new FreenetURI(updaterConfig.getString("revocationURI"));
		} catch (MalformedURLException e) {
			throw new InvalidConfigValueException("Invalid revocationURI: "+e);
		}

		if(revocationURI.lastMetaString() != null && revocationURI.lastMetaString().length() == 0) {
			// FIXME remove this hack, put in because of bad default
			System.err.println("Correcting auto-update revocation URI: Removing extra /");
			revocationURI = revocationURI.popMetaString();
		}
		
        updaterConfig.register("extURI", EXT_URI, 5,
        		true, false, "Where should the node look for updates to freenet-ext.jar?",
        		"Where should the node look for updates to freenet-ext.jar?",
        		new UpdateURICallback(true));
        
        try {
			extURI = new FreenetURI(updaterConfig.getString("extURI"));
		} catch (MalformedURLException e) {
			throw new InvalidConfigValueException("Invalid extURI: "+e);
		}

        updaterConfig.finishedInitialization();
        
        this.revocationChecker = new RevocationChecker(this);
        
	}

	public void start() throws InvalidConfigValueException {
		
		node.clientCore.alerts.register(alert);
        
        enable(wasEnabledOnStartup);
		
	}
	
	/**
	 * Is auto-update enabled?
	 */
	public boolean isEnabled() {
		synchronized(this) {
			return mainUpdater != null && mainUpdater.isRunning();
		}
	}

	/**
	 * Enable or disable auto-update.
	 * @param enable Whether auto-update should be enabled.
	 * @throws InvalidConfigValueException If enable=true and we are not running under the wrapper.
	 */
	void enable(boolean enable) throws InvalidConfigValueException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		NodeUpdater main = null, ext = null;
		synchronized(this) {
			boolean enabled = (mainUpdater != null && mainUpdater.isRunning());
			if(enabled == enable) return;
			if(!enable) {
				// Kill it
				mainUpdater.preKill();
				main = mainUpdater;
				mainUpdater = null;
				if(extUpdater != null)
					extUpdater.preKill();
				ext = extUpdater;
				extUpdater = null;
			} else {
				if((!WrapperManager.isControlledByNativeWrapper()) || (NodeStarter.extBuildNumber == -1)) {
					Logger.error(this, "Cannot update because not running under wrapper");
					throw new InvalidConfigValueException("Cannot update because not running under wrapper");
				}
				// Start it
				mainUpdater = new NodeUpdater(this, updateURI, false, Version.buildNumber());
				if(shouldUpdateExt)
					extUpdater = new NodeUpdater(this, extURI, true, NodeStarter.extBuildNumber);
			}
		}
		if(!enable) {
			if(main != null) main.kill();
			if(ext != null) ext.kill();
			revocationChecker.kill();
		} else {
			mainUpdater.start();
			if(extUpdater != null)
				extUpdater.start();
			revocationChecker.start(false);
		}
	}
	
	/**
	 * Create a NodeUpdaterManager. Called by node constructor.
	 * @param node The node object.
	 * @param config The global config object. Options will be added to a subconfig called node.updater.
	 * @return A new NodeUpdaterManager
	 * @throws InvalidConfigValueException If there is an error in the config.
	 */
	public static NodeUpdaterManager maybeCreate(Node node, Config config) throws InvalidConfigValueException {
		return new NodeUpdaterManager(node, config);
	}

	/**
	 * Get the URI for either the freenet.jar updater or the freenet-ext.jar updater.
	 * @param isExt If true, return the freenet-ext.jar update URI; if false, return the freenet.jar URI.
	 * @return See above.
	 */
	public synchronized FreenetURI getURI(boolean isExt) {
		return isExt ? extURI : updateURI;
	}

	/**
	 * Set the URI for either the freenet.jar updater or the freenet-ext.jar updater.
	 * @param isExt If true, set the freenet-ext.jar update URI; if false, set the freenet.jar update URI.
	 * @param uri The URI to set.
	 */
	public void setURI(boolean isExt, FreenetURI uri) {
		NodeUpdater updater;
		synchronized(this) {
			if(isExt) {
				if(extURI.equals(uri)) return;
				extURI = uri;
				updater = extUpdater;
			} else {
				if(updateURI.equals(uri)) return;
				updateURI = uri;
				updater = mainUpdater;
			}
		}
		if(updater == null) return;
		if(updater.isRunning()) return;
		updater.onChangeURI();
	}

	/**
	 * @return Is auto-update currently enabled?
	 */
	public synchronized boolean isAutoUpdateAllowed() {
		return isAutoUpdateAllowed;
	}

	/**
	 * Enable or disable auto-update.
	 * @param val If true, enable auto-update (and immediately update if an update is ready). If false, disable it.
	 */
	public void setAutoUpdateAllowed(boolean val) {
		synchronized(this) {
			if(val == isAutoUpdateAllowed) return;
			isAutoUpdateAllowed = val;
			if(val) {
				if(!isReadyToDeployUpdate(false)) return;
			} else return;
		}
		deployOffThread(0);
	}

	private static final int WAIT_FOR_SECOND_FETCH_TO_COMPLETE = 240*1000;
	private static final int RECENT_REVOCATION_INTERVAL = 120*1000;
	
	/** Does the updater have an update ready to deploy? May be called synchronized(this) */
	private boolean isReadyToDeployUpdate(boolean ignoreRevocation) {
		long now = System.currentTimeMillis();
		long startedMillisAgo = -1;
		synchronized(this) {
			if(!(hasNewMainJar || hasNewExtJar)) return false; // no jar
			if(hasBeenBlown) return false; // Duh
			// Don't immediately deploy if still fetching
			startedMillisAgo = now - Math.max(startedFetchingNextMainJar, startedFetchingNextExtJar);
			if(startedMillisAgo < WAIT_FOR_SECOND_FETCH_TO_COMPLETE)
				return false; // Wait for running fetch to complete
			if(!ignoreRevocation) {
				if(now - revocationChecker.lastSucceeded() < RECENT_REVOCATION_INTERVAL)
					return true;
			}
		}
		revocationChecker.start(true);
		if(ignoreRevocation) return true;
		deployOffThread(WAIT_FOR_SECOND_FETCH_TO_COMPLETE - startedMillisAgo);
		return false;
	}

	/** Check whether there is an update to deploy. If there is, do it. */
	private void deployUpdate() {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		try {
			synchronized(this) {
				if(hasBeenBlown) {
					String msg = "Trying to update but key has been blown! Message was "+revocationMessage;
					Logger.error(this, msg);
					System.err.println(msg);
					return;
				}
				if(!isEnabled()) return;
				if(!(isAutoUpdateAllowed || armed)) return;
				if(!isReadyToDeployUpdate(false)) return;
				if(isDeployingUpdate) return;
				isDeployingUpdate = true;
			}
			
			innerDeployUpdate();
		} catch (Throwable t) {
			synchronized(this) {
				isDeployingUpdate = false;
			}
		}
	}
	
	/**
	 * Deploy the update. Inner method. Doesn't check anything, just does it.
	 */
	private void innerDeployUpdate() {
		// Write the jars, config etc.
		// Then restart

		UpdateDeployContext ctx;
		try {
			ctx = new UpdateDeployContext();
		} catch (UpdaterParserException e) {
			failUpdate("Could not determine which jars are in use: "+e.getMessage());
			return;
		}

		if(writeJars(ctx)) 
			restart(ctx);
	}

	/**
	 * Write the updated jars, if necessary rewrite the wrapper.conf.
	 * @return True if this part of the update succeeded.
	 */
	private boolean writeJars(UpdateDeployContext ctx) {
		/**
		 * What do we want to do here?
		 * 1. If we have a new main jar:
		 * - If on Windows, write it to a new jar file, update the wrapper.conf to point to it.
		 * - Otherwise, write to a new jar file, then move the new jar file over the old jar file.
		 * 2. If we have a new ext jar:
		 * - Write it to a new jar file, update the wrapper.conf to point to it.
		 * 
		 * Then restart.
		 */

		boolean writtenNewJar = false;
		boolean writtenNewExt = false;
		
		boolean tryEasyWay = File.pathSeparatorChar == '/' && !hasNewExtJar;

		File mainJar = ctx.getMainJar();
		File newMainJar = ctx.getNewMainJar();
		
		if(hasNewMainJar) {
			writtenNewJar = true;
			boolean writtenToTempFile = false;
			try {
				if(newMainJar.exists()) {
					if(!newMainJar.delete()) {
						if(newMainJar.exists()) {
							System.err.println("Cannot write to preferred new jar location "+newMainJar);
							if(tryEasyWay) {
								try {
									newMainJar = File.createTempFile("freenet", ".jar", mainJar.getParentFile());
								} catch (IOException e) {
									failUpdate("Cannot write to any other location either - disk full? "+e);
									return false;
								}
								// Try writing to it
								try {
									mainUpdater.writeJarTo(newMainJar);
									writtenToTempFile = true;
								} catch (IOException e) {
									newMainJar.delete();
									failUpdate("Cannot write new jar - disk full? "+e);
									return false;
								}
							} else {
								// Try writing it to the new one even though we can't delete it.
								mainUpdater.writeJarTo(newMainJar);
							}
						} else {
							mainUpdater.writeJarTo(newMainJar);
						}
					} else {
						if(logMINOR) Logger.minor(this, "Deleted old jar "+newMainJar);
						mainUpdater.writeJarTo(newMainJar);
					}
				} else {
					mainUpdater.writeJarTo(newMainJar);
				}
			} catch (IOException e) {
				failUpdate("Cannot update: Cannot write to " + (tryEasyWay ? " temp file " : "new jar ")+newMainJar);
				return false;
			}
			
			if(tryEasyWay) {
				// Do it the easy way. Just rewrite the main jar.
				if(!newMainJar.renameTo(mainJar)) {
					Logger.error(this, "Cannot rename temp file "+newMainJar+" over original jar "+mainJar);
					if(writtenToTempFile) {
						// Fail the update - otherwise we will leak disk space
						newMainJar.delete();
						failUpdate("Cannot write to preferred new jar location and cannot rename temp file over old jar, update failed");
						return false;
					}
					// Try the hard way
				} else {
					System.err.println("Written new freenet jar: "+mainUpdater.getWrittenVersion());
					return true;
				}
			}
			
		}
		
		// Easy way didn't work or we can't do the easy way. Try the hard way.
		
		if(hasNewExtJar) {
			
			writtenNewExt = true;
			
			// Write the new ext jar
			
			File newExtJar = ctx.getNewExtJar();
			
			try {
				extUpdater.writeJarTo(newExtJar);
			} catch (IOException e) {
				failUpdate("Cannot write new ext jar to "+newExtJar);
				return false;
			}
			
		}
		
		try {
			ctx.rewriteWrapperConf(writtenNewJar, writtenNewExt);
		} catch (IOException e) {
			failUpdate("Cannot rewrite wrapper.conf: "+e);
			return false;
		} catch (UpdateCatastropheException e) {
			failUpdate(e.getMessage());
			node.clientCore.alerts.register(new SimpleUserAlert(false, "Catastrophic update failure", e.getMessage(), UserAlert.CRITICAL_ERROR));
			return false;
		}
		
		return true;
	}

	/** Restart the node. Does not return. */
	private void restart(UpdateDeployContext ctx) {
		node.getNodeStarter().restart();
		try {
			Thread.sleep(5*60*1000);
		} catch (InterruptedException e) {
			// Break
		} // in case it's still restarting
		System.err.println("Failed to restart. Exiting, please restart the node.");
		System.exit(Node.EXIT_RESTART_FAILED);
	}

	private void failUpdate(String reason) {
		Logger.error(this, "Update failed: "+reason);
		System.err.println("Update failed: "+reason);
		this.killUpdateAlerts();
		node.clientCore.alerts.register(new SimpleUserAlert(true, "Update Failed!", "Update Failed: "+reason, UserAlert.ERROR));
	}

	/** @return The revocation URI. */
	public synchronized FreenetURI getRevocationURI() {
		return revocationURI;
	}
	
	/**
	 * Set the revocation URI.
	 * @param uri The new revocation URI.
	 */
	public void setRevocationURI(FreenetURI uri) {
		synchronized(this) {
			if(revocationURI.equals(uri)) return;
			this.revocationURI = uri;
		}
		revocationChecker.onChangeRevocationURI();
	}
	
	/**
	 * Called when a new jar has been downloaded.
	 * @param isExt If true, the new jar is the ext jar; if false, it is the main jar.
	 */
	void onDownloadedNewJar(boolean isExt) {
		synchronized(this) {
			if(isExt) {
				hasNewExtJar = true;
				startedFetchingNextExtJar = -1;
			} else {
				hasNewMainJar = true;
				startedFetchingNextMainJar = -1;
			}
		}
		revocationChecker.start(true);
	}

	/**
	 * Called when the NodeUpdater starts to fetch a new version of the jar.
	 * @param isExt If true, the new jar is the ext jar; if false, it is the main jar.
	 */
	void onStartFetching(boolean isExt) {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(isExt) {
				startedFetchingNextExtJar = now;
			} else {
				startedFetchingNextMainJar = now;
			}
		}
	}
	
	public void blow(String msg){
		NodeUpdater main, ext;
		synchronized(this) {
			if(hasBeenBlown){
				Logger.error(this, "The key has ALREADY been marked as blown! Message was "+revocationMessage+" new message "+msg);
				return;
			}else{
				this.revocationMessage = msg;
				this.hasBeenBlown = true;
				// We must get to the lower part, and show the user the message
				try {
					System.err.println("THE AUTO-UPDATING SYSTEM HAS BEEN COMPROMIZED!");
					System.err.println("The auto-updating system revocation key has been inserted. It says: "+revocationMessage);
				} catch (Throwable t) {
					try {
						Logger.error(this, "Caught "+t, t);
					} catch (Throwable t1) {}
				}
			}
			main = mainUpdater;
			ext = extUpdater;
			if(main != null) main.preKill();
			if(ext != null) ext.preKill();
		}
		if(main != null) main.kill();
		if(ext != null) ext.kill();
		if(revocationAlert==null){
			revocationAlert = new RevocationKeyFoundUserAlert(msg);
			node.clientCore.alerts.register(revocationAlert);
			// we don't need to advertize updates : we are not going to do them
			killUpdateAlerts();
		}
	}

	/**
	 * Kill all UserAlerts asking the user whether he wants to update.
	 */
	private void killUpdateAlerts() {
		node.clientCore.alerts.unregister(alert);
	}

	/** Called when the RevocationChecker has got 3 DNFs on the revocation key */
	public void noRevocationFound() {
		deployUpdate(); // May have been waiting for the revocation.
		// If we're still here, we didn't update.
		node.ps.queueTimedJob(new Runnable() {
			public void run() {
				revocationChecker.start(false);
			}
		}, node.random.nextInt(24*60*60*1000)); 
	}
	
	public void arm() {
		synchronized(this) {
			armed = true;
		}
		deployOffThread(0);
	}
	
	void deployOffThread(long delay) {
		node.ps.queueTimedJob(new Runnable() {
			public void run() {
				deployUpdate();
			}
		}, delay);
	}

	/**
	 * Has the private key been revoked?
	 */
	public boolean isBlown() {
		return hasBeenBlown;
	}

	// Config callbacks
	
	class UpdaterEnabledCallback implements BooleanCallback {
		
		public boolean get() {
			return isEnabled();
		}
		
		public void set(boolean val) throws InvalidConfigValueException {
			enable(val);
		}
	}
	
	class AutoUpdateAllowedCallback implements BooleanCallback {
		
		public boolean get() {
			return isAutoUpdateAllowed();
		}
		
		public void set(boolean val) throws InvalidConfigValueException {
			setAutoUpdateAllowed(val);
		}
	}

	class UpdateURICallback implements StringCallback {

		boolean isExt;
		
		UpdateURICallback(boolean isExt) {
			this.isExt = isExt;
		}
		
		public String get() {
			return getURI(isExt).toString(false);
		}

		public void set(String val) throws InvalidConfigValueException {
			FreenetURI uri;
			try {
				uri = new FreenetURI(val);
			} catch (MalformedURLException e) {
				throw new InvalidConfigValueException("Invalid key: "+val+" : "+e);
			}
			setURI(isExt, uri);
		}

	}

	public class UpdateRevocationURICallback implements StringCallback {

		public String get() {
			return getRevocationURI().toString(false);
		}

		public void set(String val) throws InvalidConfigValueException {
			FreenetURI uri;
			try {
				uri = new FreenetURI(val);
			} catch (MalformedURLException e) {
				throw new InvalidConfigValueException("Invalid key: "+val+" : "+e);
			}
			setRevocationURI(uri);
		}
		
	}

	public synchronized boolean hasNewMainJar() {
		return hasNewMainJar;
	}

	public synchronized boolean hasNewExtJar() {
		return hasNewExtJar;
	}

	public int newMainJarVersion() {
		return mainUpdater.getFetchedVersion();
	}

	public int newExtJarVersion() {
		return extUpdater.getFetchedVersion();
	}

	public boolean fetchingNewMainJar() {
		return mainUpdater != null && mainUpdater.isFetching();
	}

	public boolean fetchingNewExtJar() {
		return extUpdater != null && extUpdater.isFetching();
	}

	public int fetchingNewMainJarVersion() {
		return mainUpdater.fetchingVersion();
	}

	public int fetchingNewExtJarVersion() {
		return extUpdater.fetchingVersion();
	}

	public boolean inFinalCheck() {
		return isReadyToDeployUpdate(true) && !isReadyToDeployUpdate(false);
	}

	public int getRevocationDNFCounter() {
		return revocationChecker.getRevocationDNFCounter();
	}

	public int getMainVersion() {
		return Version.buildNumber();
	}
	
	public int getExtVersion() {
		return NodeStarter.extBuildNumber;
	}

	public boolean isArmed() {
		return armed || isAutoUpdateAllowed;
	}

	public boolean canUpdateNow() {
		return isReadyToDeployUpdate(true);
	}

	public boolean canUpdateImmediately() {
		return isReadyToDeployUpdate(false);
	}

}
