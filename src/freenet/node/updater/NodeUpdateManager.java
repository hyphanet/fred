package freenet.node.updater;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.DatabaseDisabledException;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.SubConfig;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.Announcer;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.OpennetManager;
import freenet.node.PeerNode;
import freenet.node.RequestStarter;
import freenet.node.Version;
import freenet.node.updater.UpdateDeployContext.UpdateCatastropheException;
import freenet.node.useralerts.RevocationKeyFoundUserAlert;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UpdatedVersionAvailableUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.pluginmanager.PluginManager.OfficialPluginDescription;
import freenet.support.Logger;
import freenet.support.api.BooleanCallback;
import freenet.support.api.StringCallback;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**
 * Supervises NodeUpdater's. Enables us to easily update multiple files,
 * change the URI's on the fly, eliminates some messy code in the
 * callbacks etc.
 */
public class NodeUpdateManager {

	public final static String UPDATE_URI = "freenet:USK@BFa1voWr5PunINSZ5BGMqFwhkJTiDBBUrOZ0MYBXseg,BOrxeLzUMb6R9tEZzexymY0zyKAmBNvrU4A9Q0tAqu0,AQACAAE/update/"+Version.buildNumber();
	public final static String REVOCATION_URI = "SSK@tHlY8BK2KFB7JiO2bgeAw~e4sWU43YdJ6kmn73gjrIw,DnQzl0BYed15V8WQn~eRJxxIA-yADuI8XW7mnzEbut8,AQACAAE/revoked";
	public final static String EXT_URI = "freenet:USK@BFa1voWr5PunINSZ5BGMqFwhkJTiDBBUrOZ0MYBXseg,BOrxeLzUMb6R9tEZzexymY0zyKAmBNvrU4A9Q0tAqu0,AQACAAE/ext/"+NodeStarter.extBuildNumber;

	public static final long MAX_REVOCATION_KEY_LENGTH = 4*1024;
	public static final long MAX_REVOCATION_KEY_TEMP_LENGTH = 4*1024;
	public static final long MAX_REVOCATION_KEY_BLOB_LENGTH = 8*1024;

	public static final long MAX_MAIN_JAR_LENGTH = 16*1024*1024; // 16MB

	FreenetURI updateURI;
	FreenetURI extURI;
	FreenetURI revocationURI;

	NodeUpdater mainUpdater;
	NodeUpdater extUpdater;

	Map<String, PluginJarUpdater> pluginUpdaters;

	private boolean autoDeployPluginsOnRestart;
	boolean wasEnabledOnStartup;
	/** Is auto-update enabled? */
	volatile boolean isAutoUpdateAllowed;
	/** Has the user given the go-ahead? */
	volatile boolean armed;
	/** Should we check for freenet-ext.jar updates?
	 * Normally set only when our freenet-ext.jar is known to be out of date. */
	final boolean shouldUpdateExt;
	/** Currently deploying an update? */
	boolean isDeployingUpdate;
	final Object broadcastUOMAnnouncesSync = new Object();
	boolean broadcastUOMAnnounces = false;

	Node node;

	final RevocationChecker revocationChecker;
	private String revocationMessage;
	private volatile boolean hasBeenBlown;
	private volatile boolean peersSayBlown;
	private boolean updateSeednodes;
	private boolean updateInstallers;

	/** Is there a new main jar ready to deploy? */
	private volatile boolean hasNewMainJar;
	/** Is there a new ext jar ready to deploy? */
	private volatile boolean hasNewExtJar;
	/** If another main jar is being fetched, when did the fetch start? */
	private long startedFetchingNextMainJar;
	/** If another ext jar is being fetched, when did the fetch start? */
	private long startedFetchingNextExtJar;
	private long gotJarTime;

	private int minExtVersion;
	private int maxExtVersion;

	// Revocation alert
	private RevocationKeyFoundUserAlert revocationAlert;
	// Update alert
	private final UpdatedVersionAvailableUserAlert alert;

	public final UpdateOverMandatoryManager uom;

	private static volatile boolean logMINOR;
	private boolean disabledThisSession;

        static {
            Logger.registerClass(NodeUpdateManager.class);
        }
        
	public NodeUpdateManager(Node node, Config config) throws InvalidConfigValueException {
		this.node = node;
		this.hasBeenBlown = false;
		shouldUpdateExt = NodeStarter.extBuildNumber < NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER;
		this.alert= new UpdatedVersionAvailableUserAlert(this);
		alert.isValid(false);

        SubConfig updaterConfig = new SubConfig("node.updater", config);

        updaterConfig.register("enabled", true, 1, true, false, "NodeUpdateManager.enabled",
        		"NodeUpdateManager.enabledLong",
        		new UpdaterEnabledCallback());

		wasEnabledOnStartup = updaterConfig.getBoolean("enabled");

        // is the auto-update allowed ?
        updaterConfig.register("autoupdate", false, 2, false, true, "NodeUpdateManager.installNewVersions", "NodeUpdateManager.installNewVersionsLong",
        		new AutoUpdateAllowedCallback());
        isAutoUpdateAllowed = updaterConfig.getBoolean("autoupdate");

        updaterConfig.register("URI", UPDATE_URI, 3,
        		true, false, "NodeUpdateManager.updateURI",
        		"NodeUpdateManager.updateURILong",
        		new UpdateURICallback(false));

        try {
			updateURI = new FreenetURI(updaterConfig.getString("URI"));
			long ver = updateURI.getSuggestedEdition();
			if(ver < Version.buildNumber())
				ver = Version.buildNumber();
			updateURI = updateURI.setSuggestedEdition(ver);
		} catch (MalformedURLException e) {
			throw new InvalidConfigValueException(l10n("invalidUpdateURI", "error", e.getLocalizedMessage()));
		}

        updaterConfig.register("revocationURI",	REVOCATION_URI,4,
        		true, false, "NodeUpdateManager.revocationURI",
        		"NodeUpdateManager.revocationURILong",
        		new UpdateRevocationURICallback());

        try {
			revocationURI = new FreenetURI(updaterConfig.getString("revocationURI"));
		} catch (MalformedURLException e) {
			throw new InvalidConfigValueException(l10n("invalidRevocationURI", "error", e.getLocalizedMessage()));
		}

        updaterConfig.register("extURI", EXT_URI, 5, true, false, "NodeUpdateManager.extURI", "NodeUpdateManager.extURILong", new UpdateURICallback(true));

        try {
			extURI = new FreenetURI(updaterConfig.getString("extURI"));
			long ver = extURI.getSuggestedEdition();
			if(ver < NodeStarter.extBuildNumber)
				ver = NodeStarter.extBuildNumber;
			extURI = extURI.setSuggestedEdition(ver);
		} catch (MalformedURLException e) {
			throw new InvalidConfigValueException(l10n("invalidExtURI", "error", e.getLocalizedMessage()));
		}

		updaterConfig.register("updateSeednodes", wasEnabledOnStartup, 6, true, true, "NodeUpdateManager.updateSeednodes", "NodeUpdateManager.updateSeednodesLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return updateSeednodes;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						if(updateSeednodes == val) return;
						updateSeednodes = val;
						if(val)
							throw new NodeNeedRestartException("Must restart to fetch the seednodes");
						else
							throw new NodeNeedRestartException("Must restart to stop the seednodes fetch if it is still running");
					}

		});

		updateSeednodes = updaterConfig.getBoolean("updateSeednodes");

		updaterConfig.register("updateInstallers", wasEnabledOnStartup, 6, true, true, "NodeUpdateManager.updateInstallers", "NodeUpdateManager.updateInstallersLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return updateInstallers;
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
						if(updateInstallers == val) return;
						updateInstallers = val;
						if(val)
							throw new NodeNeedRestartException("Must restart to fetch the installers");
						else
							throw new NodeNeedRestartException("Must restart to stop the installers fetches if they are still running");
					}

		});

		updateInstallers = updaterConfig.getBoolean("updateInstallers");



        updaterConfig.finishedInitialization();

        this.revocationChecker = new RevocationChecker(this, new File(node.clientCore.getPersistentTempDir(), "revocation-key.fblob"));

        this.uom = new UpdateOverMandatoryManager(this);
        this.uom.removeOldTempFiles();

        maxExtVersion = NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER;
        minExtVersion = NodeStarter.REQUIRED_EXT_BUILD_NUMBER;

	}

	class SimplePuller implements ClientGetCallback {

		final FreenetURI freenetURI;
		final String filename;

		public SimplePuller(FreenetURI freenetURI, String filename) {
			this.freenetURI = freenetURI;
			this.filename = filename;
		}

		public void start(short priority, long maxSize) {
			HighLevelSimpleClient hlsc = node.clientCore.makeClient(priority);
			FetchContext context = hlsc.getFetchContext();
			context.maxNonSplitfileRetries = -1;
			context.maxSplitfileBlockRetries = -1;
			context.maxTempLength = maxSize;
			context.maxOutputLength = maxSize;
			ClientGetter get = new ClientGetter(this, freenetURI, context, priority, node.nonPersistentClientBulk, null, null);
			try {
				node.clientCore.clientContext.start(get);
			} catch (DatabaseDisabledException e) {
				// Impossible
			} catch (FetchException e) {
				onFailure(e, null, null);
			}
		}

		public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
			System.err.println("Failed to fetch "+filename+" : "+e);
		}

		public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
			File temp;
			FileOutputStream fos = null;
			try {
				temp = File.createTempFile(filename, ".tmp", node.getRunDir());
				temp.deleteOnExit();
				fos = new FileOutputStream(temp);
				BucketTools.copyTo(result.asBucket(), fos, -1);
				fos.close();
				fos = null;
				if(FileUtil.renameTo(temp, node.runDir().file(filename)))
					System.out.println("Successfully fetched "+filename+" for version "+Version.buildNumber());
				else
					System.out.println("Failed to rename "+temp+" to "+filename+" after fetching it from Freenet.");
			} catch (IOException e) {
				System.err.println("Fetched but failed to write out "+filename+" - please check that the node has permissions to write in "+node.getRunDir()+" and particularly the file "+filename);
				System.err.println("The error was: "+e);
				e.printStackTrace();
			} finally {
				Closer.close(fos);
			}
		}

		public void onMajorProgress(ObjectContainer container) {
			// Ignore
		}

	}

	public static final String WINDOWS_FILENAME = "freenet-latest-installer-windows.exe";
	public static final String NON_WINDOWS_FILENAME = "freenet-latest-installer-nonwindows.jar";

	public File getInstallerWindows() {
		File f = node.runDir().file(WINDOWS_FILENAME);
		if(!(f.exists() && f.canRead() && f.length() > 0)) return null;
		else return f;
	}

	public File getInstallerNonWindows() {
		File f = node.runDir().file(NON_WINDOWS_FILENAME);
		if(!(f.exists() && f.canRead() && f.length() > 0)) return null;
		else return f;
	}

	public FreenetURI getSeednodesURI() {
		return updateURI.sskForUSK().setDocName("seednodes-"+Version.buildNumber());
	}

	public FreenetURI getInstallerWindowsURI() {
		return updateURI.sskForUSK().setDocName("installer-"+Version.buildNumber());
	}

	public FreenetURI getInstallerNonWindowsURI() {
		return updateURI.sskForUSK().setDocName("wininstaller-"+Version.buildNumber());
	}

	public void start() throws InvalidConfigValueException {

		node.clientCore.alerts.register(alert);

        enable(wasEnabledOnStartup);

        // Fetch 3 files, each to a file in the runDir.

        if(updateSeednodes) {

        	SimplePuller seedrefsGetter =
        		new SimplePuller(getSeednodesURI(), Announcer.SEEDNODES_FILENAME);
        	seedrefsGetter.start(RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 1024*1024);
        }

        if(updateInstallers) {
        	SimplePuller installerGetter =
        		new SimplePuller(getInstallerWindowsURI(), NON_WINDOWS_FILENAME);
        	SimplePuller wininstallerGetter =
        		new SimplePuller(getInstallerNonWindowsURI(), WINDOWS_FILENAME);


        	installerGetter.start(RequestStarter.UPDATE_PRIORITY_CLASS, 32*1024*1024);
        	wininstallerGetter.start(RequestStarter.UPDATE_PRIORITY_CLASS, 32*1024*1024);

        }

	}

	void broadcastUOMAnnounces() {
		Message msg;
		synchronized(broadcastUOMAnnouncesSync) {
			msg = getUOMAnnouncement();
			broadcastUOMAnnounces = true;
		}
		node.peers.localBroadcast(msg, true, true, ctr);
	}

	private Message getUOMAnnouncement() {
		return DMT.createUOMAnnounce(updateURI.toString(), extURI.toString(), revocationURI.toString(), revocationChecker.hasBlown(),
				mainUpdater == null ? -1 : mainUpdater.getFetchedVersion(),
				extUpdater == null ? -1 : extUpdater.getFetchedVersion(),
				revocationChecker.lastSucceededDelta(), revocationChecker.getRevocationDNFCounter(),
				revocationChecker.getBlobSize(),
				mainUpdater == null ? -1 : mainUpdater.getBlobSize(),
				extUpdater == null ? -1 : extUpdater.getBlobSize(),
				(int)node.nodeStats.getNodeAveragePingTime(), (int)node.nodeStats.getBwlimitDelayTime());
	}

	public void maybeSendUOMAnnounce(PeerNode peer) {
		synchronized(broadcastUOMAnnouncesSync) {
			if(!broadcastUOMAnnounces) {
				if(logMINOR) Logger.minor(this, "Not sending UOM on connect: Nothing worth announcing yet");
				return; // nothing worth announcing yet
			}
		}
		boolean dontHaveUpdate;
		synchronized(this) {
			dontHaveUpdate = (mainUpdater == null || mainUpdater.getFetchedVersion() <= 0);
			if((!hasBeenBlown) && dontHaveUpdate) {
				if(logMINOR) Logger.minor(this, "Not sending UOM on connect: Don't have the update");
				return;
			}
		}
		if((!dontHaveUpdate) && hasBeenBlown && !revocationChecker.hasBlown()) {
			if(logMINOR) Logger.minor(this, "Not sending UOM on connect: Local problem causing blown key");
			// Local problem, don't broadcast.
			return;
		}
		try {
			peer.sendAsync(getUOMAnnouncement(), null, ctr);
		} catch (NotConnectedException e) {
			// Sad, but ignore it
		}
	}

	/**
	 * Is auto-update enabled?
	 */
	public synchronized boolean isEnabled() {
		return (mainUpdater != null);
	}

	/**
	 * Enable or disable auto-update.
	 * @param enable Whether auto-update should be enabled.
	 * @throws InvalidConfigValueException If enable=true and we are not running under the wrapper.
	 */
	void enable(boolean enable) throws InvalidConfigValueException {
//		if(!node.isUsingWrapper()){
//			Logger.normal(this, "Don't try to start the updater as we are not running under the wrapper.");
//			return;
//		}
		NodeUpdater main = null, ext = null;
		Map<String, PluginJarUpdater> oldPluginUpdaters = null;
		synchronized(this) {
			boolean enabled = (mainUpdater != null);
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
				oldPluginUpdaters = pluginUpdaters;
				pluginUpdaters = null;
			} else {
//				if((!WrapperManager.isControlledByNativeWrapper()) || (NodeStarter.extBuildNumber == -1)) {
//					Logger.error(this, "Cannot update because not running under wrapper");
//					throw new InvalidConfigValueException(l10n("noUpdateWithoutWrapper"));
//				}
				// Start it
				mainUpdater = new MainJarUpdater(this, updateURI, Version.buildNumber(), -1, Integer.MAX_VALUE, "main-jar-");
				extUpdater = new ExtJarUpdater(this, extURI, NodeStarter.extBuildNumber, NodeStarter.REQUIRED_EXT_BUILD_NUMBER, NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER, "ext-jar-");
				pluginUpdaters = new HashMap<String, PluginJarUpdater>();
			}
		}
		if(!enable) {
			if(main != null) main.kill();
			if(ext != null) ext.kill();
			revocationChecker.kill();
			stopPluginUpdaters(oldPluginUpdaters);
		} else {
			mainUpdater.start();
			if(extUpdater != null)
				extUpdater.start();
			revocationChecker.start(false);
			startPluginUpdaters();
		}
	}

	private void startPluginUpdaters() {
		Map<String, OfficialPluginDescription> officialPlugins = PluginManager.officialPlugins;
		for(OfficialPluginDescription plugin : officialPlugins.values()) {
			startPluginUpdater(plugin);
		}
	}

	/** @param plugName The filename for loading/config purposes for an official plugin.
	 * E.g. "Library" (no .jar) */
	public void startPluginUpdater(String plugName) {
		if(logMINOR) Logger.minor(this, "Starting plugin updater for "+plugName);
		OfficialPluginDescription plugin = PluginManager.officialPlugins.get(plugName);
		if(plugin != null)
			startPluginUpdater(plugin);
		else
			// Most likely not an official plugin
			if(logMINOR) Logger.minor(this, "No such plugin "+plugName+" in startPluginUpdater()");
	}

	void startPluginUpdater(OfficialPluginDescription plugin) {
		String name = plugin.name;
		long minVer = plugin.minimumVersion;
		// But it might already be past that ...
		PluginInfoWrapper info = node.pluginManager.getPluginInfo(name);
		if(info == null) {
			if(!(node.pluginManager.isPluginLoadedOrLoadingOrWantLoad(name))) {
				if(logMINOR) Logger.minor(this, "Plugin not loaded");
				return;
			}
		}
		minVer = Math.max(minVer, info.getPluginLongVersion());
		FreenetURI uri = updateURI.setDocName(name).setSuggestedEdition(minVer);
		PluginJarUpdater updater = new PluginJarUpdater(this, uri, (int) minVer, -1, Integer.MAX_VALUE, name+"-", name, node.pluginManager, autoDeployPluginsOnRestart);
		synchronized(this) {
			if(pluginUpdaters == null) {
				if(logMINOR) Logger.minor(this, "Updating not enabled");
				return; // Not enabled
			}
			if(pluginUpdaters.containsKey(name)) {
				if(logMINOR) Logger.minor(this, "Already in updaters list");
				return; // Already started
			}
			pluginUpdaters.put(name, updater);
		}
		updater.start();
		System.out.println("Started plugin update fetcher for "+name);
	}

	public void stopPluginUpdater(String plugName) {
		OfficialPluginDescription plugin = PluginManager.officialPlugins.get(plugName);
		if(plugin == null) return; // Not an official plugin
		PluginJarUpdater updater = null;
		synchronized(this) {
			if(pluginUpdaters == null) {
				if(logMINOR) Logger.minor(this, "Updating not enabled");
				return; // Not enabled
			}
			updater = pluginUpdaters.remove(plugName);
		}
		if(updater != null)
			updater.kill();
	}

	private void stopPluginUpdaters(Map<String, PluginJarUpdater> oldPluginUpdaters) {
		for(PluginJarUpdater u : oldPluginUpdaters.values()) {
			u.kill();
		}
	}

	/**
	 * Create a NodeUpdateManager. Called by node constructor.
	 * @param node The node object.
	 * @param config The global config object. Options will be added to a subconfig called node.updater.
	 * @return A new NodeUpdateManager
	 * @throws InvalidConfigValueException If there is an error in the config.
	 */
	public static NodeUpdateManager maybeCreate(Node node, Config config) throws InvalidConfigValueException {
		return new NodeUpdateManager(node, config);
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
		// FIXME plugins!!
		NodeUpdater updater;
		Map<String, PluginJarUpdater> oldPluginUpdaters = null;
		synchronized(this) {
			if(isExt) {
				if(extURI.equals(uri)) return;
				extURI = uri;
				updater = extUpdater;
			} else {
				if(updateURI.equals(uri)) return;
				updateURI = uri;
				updater = mainUpdater;
				oldPluginUpdaters = pluginUpdaters;
				pluginUpdaters = new HashMap<String, PluginJarUpdater>();
			}
			if(updater == null) return;
		}
		updater.onChangeURI(uri);
		stopPluginUpdaters(oldPluginUpdaters);
		startPluginUpdaters();
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
	 * @return Is auto-update currently enabled?
	 */
	public boolean isAutoUpdateAllowed() {
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
	/** After 5 minutes, deploy the update even if we haven't got 3 DNFs on the revocation key yet.
	 * Reason: we want to be able to deploy UOM updates on nodes with all TOO NEW or leaf nodes
	 * whose peers are overloaded/broken. Note that with UOM, revocation certs are automatically
	 * propagated node to node, so this should be *relatively* safe. Any better ideas, tell us. */
	private static final int REVOCATION_FETCH_TIMEOUT = 5*60*1000;

	/** Does the updater have an update ready to deploy? May be called synchronized(this) */
	private boolean isReadyToDeployUpdate(boolean ignoreRevocation) {
		long now = System.currentTimeMillis();
		long startedMillisAgo;
		synchronized(this) {
			if(mainUpdater == null) return false;
			if(!(hasNewMainJar || hasNewExtJar)) {
				if(logMINOR) Logger.minor(this, "hasNewMainJar="+hasNewMainJar+" hasNewExtJar="+hasNewExtJar);
				return false; // no jar
			}
			if(hasBeenBlown) return false; // Duh
			if(peersSayBlown) return false;
			// Don't immediately deploy if still fetching
			startedMillisAgo = now - Math.max(startedFetchingNextMainJar, startedFetchingNextExtJar);
			if(startedMillisAgo < WAIT_FOR_SECOND_FETCH_TO_COMPLETE) {
				if(logMINOR) Logger.minor(this, "Not ready: Still fetching");
				return false; // Wait for running fetch to complete
			}
			int extVer = getReadyExt();
			if(extVer < minExtVersion || extVer > maxExtVersion) {
				System.err.println("Invalid ext: current "+extVer+" must be between "+minExtVersion+" and "+maxExtVersion);
				return false;
			}
			if(!ignoreRevocation) {
				if(now - revocationChecker.lastSucceeded() < RECENT_REVOCATION_INTERVAL)
					return true;
				if(gotJarTime > 0 && now - gotJarTime >= REVOCATION_FETCH_TIMEOUT)
					return true;
			}
		}
		if(logMINOR) Logger.minor(this, "Still here in isReadyToDeployUpdate");
		// Apparently everything is ready except the revocation fetch. So start it.
		revocationChecker.start(true);
		if(ignoreRevocation) {
			if(logMINOR) Logger.minor(this, "Returning true because of ignoreRevocation");
			return true;
		}
		deployOffThread(WAIT_FOR_SECOND_FETCH_TO_COMPLETE - startedMillisAgo);
		return false;
	}

	/** Check whether there is an update to deploy. If there is, do it. */
	private void deployUpdate() {
		try {
			synchronized(this) {
				if(disabledThisSession) {
					String msg = "Not deploying update because disabled for this session (bad java version??)";
					Logger.error(this, msg);
					System.err.println(msg);
					return;
				}
				if(hasBeenBlown) {
					String msg = "Trying to update but key has been blown! Not updating, message was "+revocationMessage;
					Logger.error(this, msg);
					System.err.println(msg);
					return;
				}
				if(peersSayBlown) {
					String msg = "Trying to update but at least one peer says the key has been blown! Not updating.";
					Logger.error(this, msg);
					System.err.println(msg);
					return;

				}
				if(!isEnabled()) {
					if(logMINOR) Logger.minor(this, "Not enabled");
					return;
				}
				if(!(isAutoUpdateAllowed || armed)) {
					if(logMINOR) Logger.minor(this, "Not armed");
					return;
				}
				if(!isReadyToDeployUpdate(false)) {
					if(logMINOR) Logger.minor(this, "Not ready to deploy update");
					return;
				}
				int extVer = getReadyExt();
				if(extVer < minExtVersion || extVer > maxExtVersion) {
					if(logMINOR) Logger.minor(this, "Invalid ext: current "+extVer+" must be between "+minExtVersion+" and "+maxExtVersion);
					return;
				}
				if(isDeployingUpdate) {
					if(logMINOR) Logger.minor(this, "Already deploying update");
					return;
				}
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
		else {
			if(logMINOR) Logger.minor(this, "Did not write jars");
		}
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
		 */

		boolean writtenNewJar = false;
		boolean writtenNewExt = false;

		boolean tryEasyWay = File.pathSeparatorChar == ':' && !hasNewExtJar;

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
					System.err.println("Written new Freenet jar: "+mainUpdater.getWrittenVersion());
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
			node.clientCore.alerts.register(new SimpleUserAlert(false, l10n("updateCatastropheTitle"), e.getMessage(), l10n("updateCatastropheTitle"), UserAlert.CRITICAL_ERROR));
			return false;
		} catch (UpdaterParserException e) {
			node.clientCore.alerts.register(new SimpleUserAlert(false, l10n("updateFailedTitle"), e.getMessage(), l10n("updateFailedShort", "reason", e.getMessage()), UserAlert.CRITICAL_ERROR));
			return false;
		}

		return true;
	}

	/** Restart the node. Does not return. */
	private void restart(UpdateDeployContext ctx) {
		if(logMINOR)
			Logger.minor(this, "Restarting...");
		node.getNodeStarter().restart();
		try {
			Thread.sleep(5*60*1000);
		} catch (InterruptedException e) {
			// Break
		} // in case it's still restarting
		System.err.println("Failed to restart. Exiting, please restart the node.");
		System.exit(NodeInitException.EXIT_RESTART_FAILED);
	}

	private void failUpdate(String reason) {
		Logger.error(this, "Update failed: "+reason);
		System.err.println("Update failed: "+reason);
		this.killUpdateAlerts();
		node.clientCore.alerts.register(new SimpleUserAlert(true, l10n("updateFailedTitle"), l10n("updateFailed", "reason", reason), l10n("updateFailedShort", "reason", reason), UserAlert.ERROR));
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("NodeUpdateManager."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("NodeUpdateManager."+key, pattern, value);
	}

	/**
	 * Called when a new jar has been downloaded.
	 * @param isExt If true, the new jar is the ext jar; if false, it is the main jar.
	 * @param recommendedExt If isExt is false, the recommended ext version (upper bound)
	 * for the new jar, or -1 if it was not specified or the parse failed.
	 * @param requiredExt If isExt is false, the required ext version (lower bound) for the
	 * new jar, or -1 if it was not specified or the parse failed.
	 */
	void onDownloadedNewJar(boolean isExt, int requiredExt, int recommendedExt) {
		synchronized(this) {
			if(isExt) {
				if(extUpdater.getFetchedVersion() > NodeStarter.extBuildNumber) {
					hasNewExtJar = true;
					startedFetchingNextExtJar = -1;
					gotJarTime = System.currentTimeMillis();
					if(logMINOR)
						Logger.minor(this, "Got ext jar: "+extUpdater.getFetchedVersion());
				}
			} else {
				if(mainUpdater.getFetchedVersion() > Version.buildNumber()) {
					hasNewMainJar = true;
					startedFetchingNextMainJar = -1;
					gotJarTime = System.currentTimeMillis();
					if(logMINOR)
						Logger.minor(this, "Got main jar: "+mainUpdater.getFetchedVersion());
					if(requiredExt > -1)
						minExtVersion = requiredExt;
					if(recommendedExt > -1)
						maxExtVersion = recommendedExt;
				}
			}
		}
		if(!isExt && (requiredExt > -1 || recommendedExt > -1)) {
			extUpdater.setMinMax(requiredExt, recommendedExt);
		}
		revocationChecker.start(true);
		deployOffThread(REVOCATION_FETCH_TIMEOUT);
		if(!isAutoUpdateAllowed)
			broadcastUOMAnnounces();
	}

	private int getReadyExt() {
		int ver = NodeStarter.extBuildNumber;
		if(extUpdater != null) {
			int fetched = extUpdater.getFetchedVersion();
			if(fetched > 0) ver = fetched;
		}
		return ver;
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

	/**
	 * @param msg
	 * @param disabledNotBlown If true, the auto-updating system is broken, and should
	 * be disabled, but the problem *could* be local e.g. out of disk space and a node
	 * sends us a revocation certificate. */
	public void blow(String msg, boolean disabledNotBlown){
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
					if(disabledNotBlown) {
						System.err.println("THE AUTO-UPDATING SYSTEM HAS BEEN DISABLED!");
						System.err.println("We do not know whether this is a local problem or the auto-update system has in fact been compromised. What we do know:\n"+revocationMessage);
					} else {
						System.err.println("THE AUTO-UPDATING SYSTEM HAS BEEN COMPROMISED!");
						System.err.println("The auto-updating system revocation key has been inserted. It says: "+revocationMessage);
					}
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
			mainUpdater = null;
			extUpdater = null;
		}
		if(main != null) main.kill();
		if(ext != null) ext.kill();
		if(revocationAlert==null){
			revocationAlert = new RevocationKeyFoundUserAlert(msg, disabledNotBlown);
			node.clientCore.alerts.register(revocationAlert);
			// we don't need to advertize updates : we are not going to do them
			killUpdateAlerts();
		}
		uom.killAlert();
		broadcastUOMAnnounces();
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
		deployPluginUpdates();
		// If we're still here, we didn't update.
		broadcastUOMAnnounces();
		node.ticker.queueTimedJob(new Runnable() {
			public void run() {
				revocationChecker.start(false);
			}
		}, node.random.nextInt(24*60*60*1000));
	}

	private void deployPluginUpdates() {
		PluginJarUpdater[] updaters = null;
		synchronized(this) {
			if(this.pluginUpdaters != null)
				updaters = pluginUpdaters.values().toArray(new PluginJarUpdater[pluginUpdaters.size()]);
		}
		boolean restartRevocationFetcher = false;
		if(updaters != null) {
			for(PluginJarUpdater u : updaters) {
				if(u.onNoRevocation())
					restartRevocationFetcher = true;
			}
		}
		if(restartRevocationFetcher)
			revocationChecker.start(true, true);
	}

	public void arm() {
		armed = true;
		OpennetManager om = node.getOpennet();
		if(om != null) {
			if(om.waitingForUpdater()) {
				synchronized(this) {
					// Reannounce and count it from now.
					if(gotJarTime > 0)
						gotJarTime = System.currentTimeMillis();
				}
				om.reannounce();
			}
		}
		deployOffThread(0);
	}

	void deployOffThread(long delay) {
		node.ticker.queueTimedJob(new Runnable() {
			public void run() {
				if(logMINOR) Logger.minor(this, "Running deployOffThread");
				try {
					deployUpdate();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" trying to deployOffThread", t);
				}
				if(logMINOR) Logger.minor(this, "Run deployOffThread");
			}
		}, delay);
	}

	/**
	 * Has the private key been revoked?
	 */
	public boolean isBlown() {
		return hasBeenBlown;
	}

	public boolean hasNewMainJar() {
		return hasNewMainJar;
	}

	public boolean hasNewExtJar() {
		return hasNewExtJar;
	}

	/**
	 * What version has been fetched?
	 *
	 * This includes jar's fetched via UOM, because the UOM code feeds
	 * its results through the mainUpdater.
	 */
	public int newMainJarVersion() {
		if(mainUpdater == null) return -1;
		return mainUpdater.getFetchedVersion();
	}

	public int newExtJarVersion() {
		if(extUpdater == null) return -1;
		return extUpdater.getFetchedVersion();
	}

	public boolean fetchingNewMainJar() {
		return mainUpdater != null && mainUpdater.isFetching();
	}

	public boolean fetchingNewExtJar() {
		return extUpdater != null && extUpdater.isFetching();
	}

	public int fetchingNewMainJarVersion() {
		if(mainUpdater == null) return -1;
		return mainUpdater.fetchingVersion();
	}

	public int fetchingNewExtJarVersion() {
		if(extUpdater == null) return -1;
		return extUpdater.fetchingVersion();
	}

	public boolean inFinalCheck() {
		return isReadyToDeployUpdate(true) && !isReadyToDeployUpdate(false);
	}

	public int getRevocationDNFCounter() {
		return revocationChecker.getRevocationDNFCounter();
	}

	/**
	 * What version is the node currently running?
	 */
	public int getMainVersion() {
		return Version.buildNumber();
	}

	public int getExtVersion() {
		return NodeStarter.extBuildNumber;
	}

	public boolean isArmed() {
		return armed || isAutoUpdateAllowed;
	}

	/** Is the node able to update as soon as the revocation fetch has been completed? */
	public boolean canUpdateNow() {
		return isReadyToDeployUpdate(true);
	}

	/** Is the node able to update *immediately*? (i.e. not only is it ready in every other sense, but also a revocation
	 * fetch has completed recently enough not to need another one) */
	public boolean canUpdateImmediately() {
		return isReadyToDeployUpdate(false);
	}

	// Config callbacks

	class UpdaterEnabledCallback extends BooleanCallback  {

		@Override
		public Boolean get() {
			return isEnabled();
		}

		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			enable(val);
		}
	}

	class AutoUpdateAllowedCallback extends BooleanCallback  {

		@Override
		public Boolean get() {
			return isAutoUpdateAllowed();
		}

		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			setAutoUpdateAllowed(val);
		}
	}

	class UpdateURICallback extends StringCallback  {

		boolean isExt;

		UpdateURICallback(boolean isExt) {
			this.isExt = isExt;
		}

		@Override
		public String get() {
			return getURI(isExt).toString(false, false);
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			FreenetURI uri;
			try {
				uri = new FreenetURI(val);
			} catch (MalformedURLException e) {
				throw new InvalidConfigValueException(l10n(isExt ? "invalidExtURI" : "invalidUpdateURI", "error", e.getLocalizedMessage()));
			}
			setURI(isExt, uri);
		}
	}

	public class UpdateRevocationURICallback extends StringCallback  {

		@Override
		public String get() {
			return getRevocationURI().toString(false, false);
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			FreenetURI uri;
			try {
				uri = new FreenetURI(val);
			} catch (MalformedURLException e) {
				throw new InvalidConfigValueException(l10n("invalidRevocationURI", "error", e.getLocalizedMessage()));
			}
			setRevocationURI(uri);
		}
	}

	/** Called when a peer indicates in its UOMAnnounce that it has fetched the revocation key
	 * (or failed to do so in a way suggesting that somebody knows the key).
	 * @param source The node which is claiming this.
	 */
	void peerClaimsKeyBlown() {
		// Note that UpdateOverMandatoryManager manages the list of peers who think this.
		// All we have to do is cancel the update.

		peersSayBlown = true;
	}

	/** Called inside locks, so don't lock anything */
	public void notPeerClaimsKeyBlown() {
		peersSayBlown = false;
		node.executor.execute(new Runnable() {

			public void run() {
				isReadyToDeployUpdate(false);
			}

		}, "Check for updates");
	}

	boolean peersSayBlown() {
		return peersSayBlown;
	}

	public File getMainBlob(int version) {
		NodeUpdater updater;
		synchronized(this) {
			if(hasBeenBlown) return null;
			updater = mainUpdater;
			if(updater == null) return null;
		}
		return updater.getBlobFile(version);
	}

	public File getExtBlob(int version) {
		NodeUpdater updater;
		synchronized(this) {
			if(hasBeenBlown) return null;
			updater = extUpdater;
			if(updater == null) return null;
		}
		return updater.getBlobFile(version);
	}

	public synchronized long timeRemainingOnCheck() {
		long now = System.currentTimeMillis();
		return Math.max(0, REVOCATION_FETCH_TIMEOUT - (now - gotJarTime));
	}

	final ByteCounter ctr = new ByteCounter() {

		public void receivedBytes(int x) {
			// FIXME
		}

		public void sentBytes(int x) {
			node.nodeStats.reportUOMBytesSent(x);
		}

		public void sentPayload(int x) {
			// Ignore. It will be reported to sentBytes() as well.
		}

	};

	public void disableThisSession() {
		disabledThisSession = true;
	}

	protected long getStartedFetchingNextMainJarTimestamp() {
		return startedFetchingNextMainJar;
	}

	protected long getStartedFetchingNextExtJarTimestamp() {
		return startedFetchingNextExtJar;
	}

	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing NodeUpdateManager in database", new Exception("error"));
		return false;
	}

	public void disconnected(PeerNode pn) {
		uom.disconnected(pn);
	}

	public void deployPlugin(String fn) throws IOException {
		PluginJarUpdater updater;
		synchronized(this) {
			if(hasBeenBlown) {
				Logger.error(this, "Not deploying update for "+fn+" because revocation key has been blown!");
				return;
			}
			updater = pluginUpdaters.get(fn);
		}
		updater.writeJar();
	}

	public void deployPluginWhenReady(String fn) throws IOException {
		PluginJarUpdater updater;
		synchronized(this) {
			if(hasBeenBlown) {
				Logger.error(this, "Not deploying update for "+fn+" because revocation key has been blown!");
				return;
			}
			updater = pluginUpdaters.get(fn);
		}
		boolean wasRunning = revocationChecker.start(true, true);
		updater.arm(wasRunning);
	}

        protected boolean isSeednode() {
            return (node.isOpennetEnabled() && node.wantAnonAuth(true));
        }
}
