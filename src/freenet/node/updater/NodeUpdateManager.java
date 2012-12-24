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
import freenet.node.updater.MainJarDependenciesChecker.MainJarDependencies;
import freenet.node.updater.UpdateDeployContext.UpdateCatastropheException;
import freenet.node.useralerts.RevocationKeyFoundUserAlert;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UpdatedVersionAvailableUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.pluginmanager.PluginManager.OfficialPluginDescription;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.BooleanCallback;
import freenet.support.api.Bucket;
import freenet.support.api.StringCallback;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**
 * Supervises NodeUpdater's. Enables us to easily update multiple files, change
 * the URI's on the fly, eliminates some messy code in the callbacks etc.
 */
public class NodeUpdateManager {

	/**
	 * The last build on the old key (/update/), which includes the multi-jar
	 * updating code, but doesn't require it to work, i.e. it still uses the old
	 * freenet-ext.jar and doesn't require any other jars. Older nodes can
	 * update to this point via old UOM.
	 */
	public final static int TRANSITION_VERSION = 1421;
	/** The freenet-ext.jar build number corresponding to the old key */
	public final static int TRANSITION_VERSION_EXT = 29;

	/** The URI for post-TRANSITION_VERSION builds' freenet.jar. */
	public final static String UPDATE_URI = "USK@sabn9HY9MKLbFPp851AO98uKtsCtYHM9rqB~A5cCGW4,3yps2z06rLnwf50QU4HvsILakRBYd4vBlPtLv0elUts,AQACAAE/jar/"
			+ Version.buildNumber();

	/** Might as well be the SSK */
	public final static String LEGACY_UPDATE_URI = "freenet:SSK@BFa1voWr5PunINSZ5BGMqFwhkJTiDBBUrOZ0MYBXseg,BOrxeLzUMb6R9tEZzexymY0zyKAmBNvrU4A9Q0tAqu0,AQACAAE/update-"
			+ TRANSITION_VERSION;
	/**
	 * Pre-TRANSITION_VERSION builds needed to fetch freenet-ext.jar via an
	 * updater of its own.
	 */
	public final static String LEGACY_EXT_URI = "freenet:SSK@BFa1voWr5PunINSZ5BGMqFwhkJTiDBBUrOZ0MYBXseg,BOrxeLzUMb6R9tEZzexymY0zyKAmBNvrU4A9Q0tAqu0,AQACAAE/ext-"
			+ TRANSITION_VERSION_EXT;

	public final static String REVOCATION_URI = "SSK@tHlY8BK2KFB7JiO2bgeAw~e4sWU43YdJ6kmn73gjrIw,DnQzl0BYed15V8WQn~eRJxxIA-yADuI8XW7mnzEbut8,AQACAAE/revoked";
	public static final long MAX_REVOCATION_KEY_LENGTH = 4 * 1024;
	public static final long MAX_REVOCATION_KEY_TEMP_LENGTH = 4 * 1024;
	public static final long MAX_REVOCATION_KEY_BLOB_LENGTH = 8 * 1024;

	public static final long MAX_MAIN_JAR_LENGTH = 16 * 1024 * 1024; // 16MB

	public static final FreenetURI transitionMainJarURI;
	public static final FreenetURI transitionExtJarURI;
	
	public static final FreenetURI transitionMainJarURIAsUSK;
	public static final FreenetURI transitionExtJarURIAsUSK;
	

	public static final String transitionMainJarFilename = "legacy-freenet-jar-"
			+ TRANSITION_VERSION + ".fblob";
	public static final String transitionExtJarFilename = "legacy-freenet-ext-jar-"
			+ TRANSITION_VERSION_EXT + ".fblob";
	
	public final File transitionMainJarFile;
	public final File transitionExtJarFile;

	static {
		try {
			transitionMainJarURI = new FreenetURI(LEGACY_UPDATE_URI);
			transitionExtJarURI = new FreenetURI(LEGACY_EXT_URI);
			transitionMainJarURIAsUSK = transitionMainJarURI.uskForSSK();
			transitionExtJarURIAsUSK = transitionExtJarURI.uskForSSK();
		} catch (MalformedURLException e) {
			throw new Error(e);
		}
	}

	private FreenetURI updateURI;
	private FreenetURI revocationURI;

	private final LegacyJarFetcher transitionMainJarFetcher;
	private final LegacyJarFetcher transitionExtJarFetcher;

	private MainJarUpdater mainUpdater;

	private Map<String, PluginJarUpdater> pluginUpdaters;

	private boolean autoDeployPluginsOnRestart;
	private final boolean wasEnabledOnStartup;
	/** Is auto-update enabled? */
	private volatile boolean isAutoUpdateAllowed;
	/** Has the user given the go-ahead? */
	private volatile boolean armed;
	/** Currently deploying an update? Set when we start to deploy an update.
	 * Which means it should not be un-set, except in the case of a severe
	 * error causing a valid update to fail. However, it is un-set in this
	 * case, so that we can try again with another build. */
	private boolean isDeployingUpdate;
	private final Object broadcastUOMAnnouncesSync = new Object();
	private boolean broadcastUOMAnnouncesOld = false;
	private boolean broadcastUOMAnnouncesNew = false;

	public final Node node;

	final RevocationChecker revocationChecker;
	private String revocationMessage;
	private volatile boolean hasBeenBlown;
	private volatile boolean peersSayBlown;
	private boolean updateSeednodes;
	private boolean updateInstallers;
	// FIXME make configurable
	private boolean updateIPToCountry = true;

	/** Is there a new main jar ready to deploy? */
	private volatile boolean hasNewMainJar;
	/** If another main jar is being fetched, when did the fetch start? */
	private long startedFetchingNextMainJar;
	private long gotJarTime;

	// Revocation alert
	private RevocationKeyFoundUserAlert revocationAlert;
	// Update alert
	private final UpdatedVersionAvailableUserAlert alert;

	public final LegacyUpdateOverMandatoryManager legacyUOM;
	public final UpdateOverMandatoryManager uom;

	private static volatile boolean logMINOR;
	private boolean disabledThisSession;

	private MainJarDependencies latestMainJarDependencies;
	private int dependenciesValidForBuild;

	/** The version we have fetched and will deploy. */
	private int fetchedMainJarVersion;
	/** The jar of the version we have fetched and will deploy. */
	private Bucket fetchedMainJarData;
	
	/** The blob file for the current version, for UOM */
	private File currentVersionBlobFile;

	/**
	 * The version we have fetched and aren't using because we are already
	 * deploying.
	 */
	private int maybeNextMainJarVersion;
	/**
	 * The version we have fetched and aren't using because we are already
	 * deploying.
	 */
	private Bucket maybeNextMainJarData;
	
	static final String TEMP_BLOB_SUFFIX = ".updater.fblob.tmp";
	static final String TEMP_FILE_SUFFIX = ".updater.tmp";

	static {
		Logger.registerClass(NodeUpdateManager.class);
	}

	public NodeUpdateManager(Node node, Config config)
			throws InvalidConfigValueException {
		this.node = node;
		this.hasBeenBlown = false;
		this.alert = new UpdatedVersionAvailableUserAlert(this);
		alert.isValid(false);

		SubConfig updaterConfig = new SubConfig("node.updater", config);

		updaterConfig.register("enabled", true, 1, false, false,
				"NodeUpdateManager.enabled", "NodeUpdateManager.enabledLong",
				new UpdaterEnabledCallback());

		wasEnabledOnStartup = updaterConfig.getBoolean("enabled");

		// is the auto-update allowed ?
		updaterConfig.register("autoupdate", false, 2, false, true,
				"NodeUpdateManager.installNewVersions",
				"NodeUpdateManager.installNewVersionsLong",
				new AutoUpdateAllowedCallback());
		isAutoUpdateAllowed = updaterConfig.getBoolean("autoupdate");

		updaterConfig
				.register("URI", UPDATE_URI, 3, true, true,
						"NodeUpdateManager.updateURI",
						"NodeUpdateManager.updateURILong",
						new UpdateURICallback());

		try {
			updateURI = new FreenetURI(updaterConfig.getString("URI"));
		} catch (MalformedURLException e) {
			throw new InvalidConfigValueException(l10n("invalidUpdateURI",
					"error", e.getLocalizedMessage()));
		}
		updateURI = updateURI.setSuggestedEdition(Version.buildNumber());
		// FIXME remove, or at least disable, pre-1421 backward compatibility code in 6 months or so.
		// It might be worth keeping it around in case we have to do another transition?
		if(updateURI.setSuggestedEdition(TRANSITION_VERSION).equals(transitionMainJarURIAsUSK)) {
			System.out.println("Updating config to new update key.");
			try {
				updateURI = new FreenetURI(UPDATE_URI);
			} catch (MalformedURLException e1) {
				RuntimeException e = new RuntimeException("Impossible: Cannot parse default update URI!");
				e.initCause(e1);
			}
			config.store();
		}
		if(updateURI.hasMetaStrings())
			throw new InvalidConfigValueException(l10n("updateURIMustHaveNoMetaStrings"));
		if(!updateURI.isUSK())
			throw new InvalidConfigValueException(l10n("updateURIMustBeAUSK"));

		updaterConfig.register("revocationURI", REVOCATION_URI, 4, true, false,
				"NodeUpdateManager.revocationURI",
				"NodeUpdateManager.revocationURILong",
				new UpdateRevocationURICallback());

		try {
			revocationURI = new FreenetURI(
					updaterConfig.getString("revocationURI"));
		} catch (MalformedURLException e) {
			throw new InvalidConfigValueException(l10n("invalidRevocationURI",
					"error", e.getLocalizedMessage()));
		}

		LegacyJarFetcher.LegacyFetchCallback legacyFetcherCallback = new LegacyJarFetcher.LegacyFetchCallback() {

			@Override
			public void onSuccess(LegacyJarFetcher fetcher) {
				if (transitionMainJarFetcher.fetched()
						&& transitionExtJarFetcher.fetched()) {
					System.out.println("Got legacy jars, announcing...");
					broadcastUOMAnnouncesOld();
				}
			}

			@Override
			public void onFailure(FetchException e, LegacyJarFetcher fetcher) {
				Logger.error(
						this,
						"Failed to fetch "
								+ fetcher.saveTo
								+ " : UPDATE OVER MANDATORY WILL NOT WORK WITH OLDER NODES THAN "
								+ TRANSITION_VERSION + " : " + e, e);
				System.err
						.println("Failed to fetch "
								+ fetcher.saveTo
								+ " : UPDATE OVER MANDATORY WILL NOT WORK WITH OLDER NODES THAN "
								+ TRANSITION_VERSION + " : " + e);
			}

		};

		transitionMainJarFile = new File(node.clientCore.getPersistentTempDir(), transitionMainJarFilename);
		transitionExtJarFile = new File(node.clientCore.getPersistentTempDir(), transitionExtJarFilename);
		transitionMainJarFetcher = new LegacyJarFetcher(transitionMainJarURI,
				transitionMainJarFile, node.clientCore,
				legacyFetcherCallback);
		transitionExtJarFetcher = new LegacyJarFetcher(transitionExtJarURI,
				transitionExtJarFile, node.clientCore,
				legacyFetcherCallback);

		updaterConfig.register("updateSeednodes", wasEnabledOnStartup, 6, true,
				true, "NodeUpdateManager.updateSeednodes",
				"NodeUpdateManager.updateSeednodesLong", new BooleanCallback() {

					@Override
					public Boolean get() {
						return updateSeednodes;
					}

					@Override
					public void set(Boolean val)
							throws InvalidConfigValueException,
							NodeNeedRestartException {
						if (updateSeednodes == val)
							return;
						updateSeednodes = val;
						if (val)
							throw new NodeNeedRestartException(
									"Must restart to fetch the seednodes");
						else
							throw new NodeNeedRestartException(
									"Must restart to stop the seednodes fetch if it is still running");
					}

				});

		updateSeednodes = updaterConfig.getBoolean("updateSeednodes");

		updaterConfig.register("updateInstallers", wasEnabledOnStartup, 6,
				true, true, "NodeUpdateManager.updateInstallers",
				"NodeUpdateManager.updateInstallersLong",
				new BooleanCallback() {

					@Override
					public Boolean get() {
						return updateInstallers;
					}

					@Override
					public void set(Boolean val)
							throws InvalidConfigValueException,
							NodeNeedRestartException {
						if (updateInstallers == val)
							return;
						updateInstallers = val;
						if (val)
							throw new NodeNeedRestartException(
									"Must restart to fetch the installers");
						else
							throw new NodeNeedRestartException(
									"Must restart to stop the installers fetches if they are still running");
					}

				});

		updateInstallers = updaterConfig.getBoolean("updateInstallers");

		updaterConfig.finishedInitialization();

		this.revocationChecker = new RevocationChecker(this, new File(
				node.clientCore.getPersistentTempDir(), "revocation-key.fblob"));

		this.legacyUOM = new LegacyUpdateOverMandatoryManager(this);
		this.uom = new UpdateOverMandatoryManager(this);
		this.uom.removeOldTempFiles();
	}

	class SimplePuller implements ClientGetCallback {

		final FreenetURI freenetURI;
		final String filename;

		public SimplePuller(FreenetURI freenetURI, String filename) {
			this.freenetURI = freenetURI;
			this.filename = filename;
		}

		public void start(short priority, long maxSize) {
			HighLevelSimpleClient hlsc = node.clientCore.makeClient(priority,
					false, false);
			FetchContext context = hlsc.getFetchContext();
			context.maxNonSplitfileRetries = -1;
			context.maxSplitfileBlockRetries = -1;
			context.maxTempLength = maxSize;
			context.maxOutputLength = maxSize;
			ClientGetter get = new ClientGetter(this, freenetURI, context,
					priority, node.nonPersistentClientBulk, null, null, null);
			try {
				node.clientCore.clientContext.start(get);
			} catch (DatabaseDisabledException e) {
				// Impossible
			} catch (FetchException e) {
				onFailure(e, null, null);
			}
		}

		@Override
		public void onFailure(FetchException e, ClientGetter state,
				ObjectContainer container) {
			System.err.println("Failed to fetch " + filename + " : " + e);
		}

		@Override
		public void onSuccess(FetchResult result, ClientGetter state,
				ObjectContainer container) {
			File temp;
			FileOutputStream fos = null;
			try {
				temp = File.createTempFile(filename, ".tmp", node.getRunDir());
				temp.deleteOnExit();
				fos = new FileOutputStream(temp);
				BucketTools.copyTo(result.asBucket(), fos, -1);
				fos.close();
				fos = null;
				for (int i = 0; i < 10; i++) {
					// FIXME add a callback in case it's being used on Windows.
					if (FileUtil.renameTo(temp, node.runDir().file(filename))) {
						System.out.println("Successfully fetched " + filename
								+ " for version " + Version.buildNumber());
						break;
					} else {
						System.out
								.println("Failed to rename " + temp + " to "
										+ filename
										+ " after fetching it from Freenet.");
						try {
							Thread.sleep(1000 + node.fastWeakRandom
									.nextInt(((int) (1000 * Math.min(
											Math.pow(2, i), 15 * 60 * 1000)))));
						} catch (InterruptedException e) {
							// Ignore
						}
					}
				}
				temp.delete();
			} catch (IOException e) {
				System.err
						.println("Fetched but failed to write out "
								+ filename
								+ " - please check that the node has permissions to write in "
								+ node.getRunDir()
								+ " and particularly the file " + filename);
				System.err.println("The error was: " + e);
				e.printStackTrace();
			} finally {
				Closer.close(fos);
			}
		}

		@Override
		public void onMajorProgress(ObjectContainer container) {
			// Ignore
		}

	}

	public static final String WINDOWS_FILENAME = "freenet-latest-installer-windows.exe";
	public static final String NON_WINDOWS_FILENAME = "freenet-latest-installer-nonwindows.jar";
	public static final String IPV4_TO_COUNTRY_FILENAME = "IpToCountry.dat";

	public File getInstallerWindows() {
		File f = node.runDir().file(WINDOWS_FILENAME);
		if (!(f.exists() && f.canRead() && f.length() > 0))
			return null;
		else
			return f;
	}

	public File getInstallerNonWindows() {
		File f = node.runDir().file(NON_WINDOWS_FILENAME);
		if (!(f.exists() && f.canRead() && f.length() > 0))
			return null;
		else
			return f;
	}

	public FreenetURI getSeednodesURI() {
		return updateURI.sskForUSK().setDocName(
				"seednodes-" + Version.buildNumber());
	}

	public FreenetURI getInstallerWindowsURI() {
		return updateURI.sskForUSK().setDocName(
				"installer-" + Version.buildNumber());
	}

	public FreenetURI getInstallerNonWindowsURI() {
		return updateURI.sskForUSK().setDocName(
				"wininstaller-" + Version.buildNumber());
	}

	public FreenetURI getIPv4ToCountryURI() {
		return updateURI.sskForUSK().setDocName(
				"iptocountryv4-" + Version.buildNumber());
	}

	public void start() throws InvalidConfigValueException {

		node.clientCore.alerts.register(alert);
		
		enable(wasEnabledOnStartup);

		// Fetch 3 files, each to a file in the runDir.

		if (updateSeednodes) {

			SimplePuller seedrefsGetter = new SimplePuller(getSeednodesURI(),
					Announcer.SEEDNODES_FILENAME);
			seedrefsGetter.start(
					RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
					1024 * 1024);
		}

		if (updateInstallers) {
			SimplePuller installerGetter = new SimplePuller(
					getInstallerWindowsURI(), NON_WINDOWS_FILENAME);
			SimplePuller wininstallerGetter = new SimplePuller(
					getInstallerNonWindowsURI(), WINDOWS_FILENAME);

			installerGetter.start(RequestStarter.UPDATE_PRIORITY_CLASS,
					32 * 1024 * 1024);
			wininstallerGetter.start(RequestStarter.UPDATE_PRIORITY_CLASS,
					32 * 1024 * 1024);

		}

		if (updateIPToCountry) {
			SimplePuller ip4Getter = new SimplePuller(getIPv4ToCountryURI(),
					IPV4_TO_COUNTRY_FILENAME);
			ip4Getter.start(RequestStarter.UPDATE_PRIORITY_CLASS,
					8 * 1024 * 1024);
		}
		
	}

	void broadcastUOMAnnouncesOld() {
		boolean mainJarAvailable = transitionMainJarFetcher == null ? false
				: transitionMainJarFetcher.fetched();
		boolean extJarAvailable = transitionExtJarFetcher == null ? false
				: transitionExtJarFetcher.fetched();
		Message msg;
		if(!(mainJarAvailable && extJarAvailable)) return;
		synchronized (broadcastUOMAnnouncesSync) {
			if(broadcastUOMAnnouncesOld && !hasBeenBlown) return;
			broadcastUOMAnnouncesOld = true;
			msg = getOldUOMAnnouncement();
		}
		node.peers.localBroadcast(msg, true, true, ctr);
	}

	void broadcastUOMAnnouncesNew() {
		if(logMINOR) Logger.minor(this, "Broadcast UOM announcements (new)");
		long size = canAnnounceUOMNew();
		Message msg;
		if(size <= 0 && !hasBeenBlown) return;
		synchronized (broadcastUOMAnnouncesSync) {
			if(broadcastUOMAnnouncesNew && !hasBeenBlown) return;
			broadcastUOMAnnouncesNew = true;
			msg = getNewUOMAnnouncement(size);
		}
		if(logMINOR) Logger.minor(this, "Broadcasting UOM announcements (new)");
		node.peers.localBroadcast(msg, true, true, ctr);
	}

	/** Return the length of the data fetched for the current version, or -1. */
	private long canAnnounceUOMNew() {
		Bucket data;
		synchronized(this) {
			if(hasNewMainJar && armed) {
				if(logMINOR) Logger.minor(this, "Will update soon, not offering UOM.");
				return -1;
			}
			if(fetchedMainJarVersion <= 0) {
				if(logMINOR) Logger.minor(this, "Not fetched yet");
				return -1;
			} else if(fetchedMainJarVersion != Version.buildNumber()) {
				// Don't announce UOM unless we've successfully started the jar.
				if(logMINOR) Logger.minor(this, "Downloaded a different version than the one we are running, not offering UOM.");
				return -1;
			}
			data = fetchedMainJarData;
		}
		if(logMINOR) Logger.minor(this, "Got data for UOM: "+data+" size "+data.size());
		return data.size();
	}

	private Message getOldUOMAnnouncement() {
		boolean mainJarAvailable = transitionMainJarFetcher == null ? false
				: transitionMainJarFetcher.fetched();
		boolean extJarAvailable = transitionExtJarFetcher == null ? false
				: transitionExtJarFetcher.fetched();
		return DMT.createUOMAnnounce(transitionMainJarURIAsUSK.toString(),
				transitionExtJarURIAsUSK.toString(),
				revocationURI.toString(), revocationChecker.hasBlown(),
				mainJarAvailable ? TRANSITION_VERSION : -1,
				extJarAvailable ? TRANSITION_VERSION_EXT : -1,
				revocationChecker.lastSucceededDelta(), revocationChecker
						.getRevocationDNFCounter(), revocationChecker
						.getBlobSize(),
				mainJarAvailable ? transitionMainJarFetcher.getBlobSize() : -1,
				extJarAvailable ? transitionExtJarFetcher.getBlobSize() : -1,
				(int) node.nodeStats.getNodeAveragePingTime(),
				(int) node.nodeStats.getBwlimitDelayTime());
	}

	private Message getNewUOMAnnouncement(long blobSize) {
		int fetchedVersion = blobSize <= 0 ? -1 : Version.buildNumber();
		if(blobSize <= 0) fetchedVersion = -1;
		return DMT.createUOMAnnouncement(updateURI.toString(), revocationURI
				.toString(), revocationChecker.hasBlown(), fetchedVersion,
				revocationChecker.lastSucceededDelta(), revocationChecker
						.getRevocationDNFCounter(), revocationChecker
						.getBlobSize(),
				blobSize,
				(int) node.nodeStats.getNodeAveragePingTime(),
				(int) node.nodeStats.getBwlimitDelayTime());
	}

	public void maybeSendUOMAnnounce(PeerNode peer) {
		boolean sendOld, sendNew;
		synchronized (broadcastUOMAnnouncesSync) {
			if (!(broadcastUOMAnnouncesOld || broadcastUOMAnnouncesNew)) {
				if (logMINOR)
					Logger.minor(this,
							"Not sending UOM (any) on connect: Nothing worth announcing yet");
				return; // nothing worth announcing yet
			}
			sendOld = broadcastUOMAnnouncesOld;
			sendNew = broadcastUOMAnnouncesNew;
		}
		if (hasBeenBlown && !revocationChecker.hasBlown()) {
			if (logMINOR)
				Logger.minor(this,
						"Not sending UOM (any) on connect: Local problem causing blown key");
			// Local problem, don't broadcast.
			return;
		}
		long size = canAnnounceUOMNew();
		try {
			if (sendOld || hasBeenBlown)
				peer.sendAsync(getOldUOMAnnouncement(), null, ctr);
			if (sendNew || hasBeenBlown)
				peer.sendAsync(getNewUOMAnnouncement(size), null, ctr);
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
	 * 
	 * @param enable
	 *            Whether auto-update should be enabled.
	 * @throws InvalidConfigValueException
	 *             If enable=true and we are not running under the wrapper.
	 */
	void enable(boolean enable) throws InvalidConfigValueException {
		// FIXME 194eb7bb6f295e52d18378d805bd315c95030b24 is doubtful and incomplete.
		// if(!node.isUsingWrapper()){
		// Logger.normal(this,
		// "Don't try to start the updater as we are not running under the wrapper.");
		// return;
		// }
		NodeUpdater main = null;
		Map<String, PluginJarUpdater> oldPluginUpdaters = null;
		// We need to run the revocation checker even if auto-update is
		// disabled.
		// Two reasons:
		// 1. For the benefit of other nodes, and because even if auto-update is
		// off, it's something the user should probably know about.
		// 2. When the key is blown, we turn off auto-update!!!!
		revocationChecker.start(false);
		synchronized (this) {
			boolean enabled = (mainUpdater != null);
			if (enabled == enable)
				return;
			if (!enable) {
				// Kill it
				mainUpdater.preKill();
				main = mainUpdater;
				mainUpdater = null;
				oldPluginUpdaters = pluginUpdaters;
				pluginUpdaters = null;
				disabledNotBlown = false;
			} else {
				// if((!WrapperManager.isControlledByNativeWrapper()) ||
				// (NodeStarter.extBuildNumber == -1)) {
				// Logger.error(this,
				// "Cannot update because not running under wrapper");
				// throw new
				// InvalidConfigValueException(l10n("noUpdateWithoutWrapper"));
				// }
				// Start it
				mainUpdater = new MainJarUpdater(this, updateURI,
						Version.buildNumber(), -1, Integer.MAX_VALUE,
						"main-jar-");
				pluginUpdaters = new HashMap<String, PluginJarUpdater>();
			}
		}
		if (!enable) {
			if (main != null)
				main.kill();
			stopPluginUpdaters(oldPluginUpdaters);
			transitionMainJarFetcher.stop();
			transitionExtJarFetcher.stop();
		} else {
			// FIXME copy it, dodgy locking.
			try {
				// Must be run before starting everything else as it cleans up tempfiles too.
				mainUpdater.cleanupDependencies();
			} catch (Throwable t) {
				// Don't let it block startup, but be very loud!
				Logger.error(this, "Caught "+t+" setting up Update Over Mandatory", t);
				System.err.println("Updater error: "+t);
				t.printStackTrace();
			}
			mainUpdater.start();
			startPluginUpdaters();
			transitionMainJarFetcher.start();
			transitionExtJarFetcher.start();
		}
	}

	private void startPluginUpdaters() {
		for(OfficialPluginDescription plugin : PluginManager.getOfficialPlugins()) {
			startPluginUpdater(plugin.name);
		}
	}

	/**
	 * @param plugName
	 *            The filename for loading/config purposes for an official
	 *            plugin. E.g. "Library" (no .jar)
	 */
	public void startPluginUpdater(String plugName) {
		if (logMINOR)
			Logger.minor(this, "Starting plugin updater for " + plugName);
		OfficialPluginDescription plugin = PluginManager.getOfficialPlugin(plugName);
		if (plugin != null)
			startPluginUpdater(plugin);
		else
		// Most likely not an official plugin
		if (logMINOR)
			Logger.minor(this, "No such plugin " + plugName
					+ " in startPluginUpdater()");
	}

	void startPluginUpdater(OfficialPluginDescription plugin) {
		String name = plugin.name;
		long minVer = plugin.minimumVersion;
		// But it might already be past that ...
		PluginInfoWrapper info = node.pluginManager.getPluginInfo(name);
		if (info == null) {
			if (!(node.pluginManager.isPluginLoadedOrLoadingOrWantLoad(name))) {
				if (logMINOR)
					Logger.minor(this, "Plugin not loaded");
				return;
			}
		}
		if (info != null)
			minVer = Math.max(minVer, info.getPluginLongVersion());
		FreenetURI uri = updateURI.setDocName(name).setSuggestedEdition(minVer);
		PluginJarUpdater updater = new PluginJarUpdater(this, uri,
				(int) minVer, -1, Integer.MAX_VALUE, name + "-", name,
				node.pluginManager, autoDeployPluginsOnRestart);
		synchronized (this) {
			if (pluginUpdaters == null) {
				if (logMINOR)
					Logger.minor(this, "Updating not enabled");
				return; // Not enabled
			}
			if (pluginUpdaters.containsKey(name)) {
				if (logMINOR)
					Logger.minor(this, "Already in updaters list");
				return; // Already started
			}
			pluginUpdaters.put(name, updater);
		}
		updater.start();
		System.out.println("Started plugin update fetcher for " + name);
	}

	public void stopPluginUpdater(String plugName) {
		OfficialPluginDescription plugin = PluginManager.getOfficialPlugin(plugName);
		if (plugin == null)
			return; // Not an official plugin
		PluginJarUpdater updater = null;
		synchronized (this) {
			if (pluginUpdaters == null) {
				if (logMINOR)
					Logger.minor(this, "Updating not enabled");
				return; // Not enabled
			}
			updater = pluginUpdaters.remove(plugName);
		}
		if (updater != null)
			updater.kill();
	}

	private void stopPluginUpdaters(
			Map<String, PluginJarUpdater> oldPluginUpdaters) {
		for (PluginJarUpdater u : oldPluginUpdaters.values()) {
			u.kill();
		}
	}

	/**
	 * Create a NodeUpdateManager. Called by node constructor.
	 * 
	 * @param node
	 *            The node object.
	 * @param config
	 *            The global config object. Options will be added to a subconfig
	 *            called node.updater.
	 * @return A new NodeUpdateManager
	 * @throws InvalidConfigValueException
	 *             If there is an error in the config.
	 */
	public static NodeUpdateManager maybeCreate(Node node, Config config)
			throws InvalidConfigValueException {
		return new NodeUpdateManager(node, config);
	}

	/**
	 * Get the URI for freenet.jar.
	 */
	public synchronized FreenetURI getURI() {
		return updateURI;
	}

	/**
	 * Set the URI freenet.jar should be updated from.
	 * 
	 * @param uri
	 *            The URI to set.
	 */
	public void setURI(FreenetURI uri) {
		// FIXME plugins!!
		NodeUpdater updater;
		Map<String, PluginJarUpdater> oldPluginUpdaters = null;
		synchronized (this) {
			if (updateURI.equals(uri))
				return;
			updateURI = uri;
			updateURI = updateURI.setSuggestedEdition(Version.buildNumber());
			updater = mainUpdater;
			oldPluginUpdaters = pluginUpdaters;
			pluginUpdaters = new HashMap<String, PluginJarUpdater>();
			if (updater == null)
				return;
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
	 * 
	 * @param uri
	 *            The new revocation URI.
	 */
	public void setRevocationURI(FreenetURI uri) {
		synchronized (this) {
			if (revocationURI.equals(uri))
				return;
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
	 * 
	 * @param val
	 *            If true, enable auto-update (and immediately update if an
	 *            update is ready). If false, disable it.
	 */
	public void setAutoUpdateAllowed(boolean val) {
		synchronized (this) {
			if (val == isAutoUpdateAllowed)
				return;
			isAutoUpdateAllowed = val;
			if (val) {
				if (!isReadyToDeployUpdate(false))
					return;
			} else
				return;
		}
		deployOffThread(0, false);
	}

	private static final int WAIT_FOR_SECOND_FETCH_TO_COMPLETE = 240 * 1000;
	private static final int RECENT_REVOCATION_INTERVAL = 120 * 1000;
	/**
	 * After 5 minutes, deploy the update even if we haven't got 3 DNFs on the
	 * revocation key yet. Reason: we want to be able to deploy UOM updates on
	 * nodes with all TOO NEW or leaf nodes whose peers are overloaded/broken.
	 * Note that with UOM, revocation certs are automatically propagated node to
	 * node, so this should be *relatively* safe. Any better ideas, tell us.
	 */
	private static final int REVOCATION_FETCH_TIMEOUT = 5 * 60 * 1000;

	/**
	 * Does the updater have an update ready to deploy? May be called
	 * synchronized(this)
	 */
	private boolean isReadyToDeployUpdate(boolean ignoreRevocation) {
		long now = System.currentTimeMillis();
		int waitForNextJar = -1;
		synchronized (this) {
			if (mainUpdater == null)
				return false;
			if (!(hasNewMainJar)) {
				return false; // no jar
			}
			if (hasBeenBlown)
				return false; // Duh
			if (peersSayBlown) {
				if(logMINOR) Logger.minor(this, "Not deploying, peers say blown");
				return false;
			}
			// Don't immediately deploy if still fetching
			if (startedFetchingNextMainJar > 0) {
				waitForNextJar = (int) (startedFetchingNextMainJar
						+ WAIT_FOR_SECOND_FETCH_TO_COMPLETE - now);
				if (waitForNextJar > 0) {
					if (logMINOR)
						Logger.minor(this, "Not ready: Still fetching");
					// Wait for running fetch to complete
				}
			}

			// Check dependencies.
			if (this.latestMainJarDependencies == null) {
				if (logMINOR)
					Logger.minor(this, "Dependencies not available");
				return false;
			}
			if (this.fetchedMainJarVersion != this.dependenciesValidForBuild) {
				if (logMINOR)
					Logger.minor(this,
							"Not deploying because dependencies are older version "
									+ dependenciesValidForBuild
									+ " - new version " + fetchedMainJarVersion
									+ " may not start");
				return false;
			}

			// Check revocation.
			if (waitForNextJar <= 0) {
				if (!ignoreRevocation) {
					if (now - revocationChecker.lastSucceeded() < RECENT_REVOCATION_INTERVAL) {
						if(logMINOR) Logger.minor(this, "Ready to deploy (revocation checker succeeded recently)");
						return true;
					}
					if (gotJarTime > 0
							&& now - gotJarTime >= REVOCATION_FETCH_TIMEOUT) {
						if(logMINOR) Logger.minor(this, "Ready to deploy (got jar before timeout)");
						return true;
					}
				}
			}
		}
		if (logMINOR)
			Logger.minor(this, "Still here in isReadyToDeployUpdate");
		// Apparently everything is ready except the revocation fetch. So start
		// it.
		revocationChecker.start(true);
		if (ignoreRevocation) {
			if (logMINOR)
				Logger.minor(this, "Returning true because of ignoreRevocation");
			return true;
		}
		int waitTime = Math.max(REVOCATION_FETCH_TIMEOUT, waitForNextJar);
		if(logMINOR) Logger.minor(this, "Will deploy in "+waitTime+"ms");
		deployOffThread(waitTime, false);
		return false;
	}

	/** Check whether there is an update to deploy. If there is, do it. */
	private void deployUpdate() {
		boolean started = false;
		boolean success = false;
		try {
			MainJarDependencies deps;
			synchronized (this) {
				if (disabledThisSession) {
					String msg = "Not deploying update because disabled for this session (bad java version??)";
					Logger.error(this, msg);
					System.err.println(msg);
					return;
				}
				if (hasBeenBlown) {
					String msg = "Trying to update but key has been blown! Not updating, message was "
							+ revocationMessage;
					Logger.error(this, msg);
					System.err.println(msg);
					return;
				}
				if (peersSayBlown) {
					String msg = "Trying to update but at least one peer says the key has been blown! Not updating.";
					Logger.error(this, msg);
					System.err.println(msg);
					return;

				}
				if (!isEnabled()) {
					if (logMINOR)
						Logger.minor(this, "Not enabled");
					return;
				}
				if (!(isAutoUpdateAllowed || armed)) {
					if (logMINOR)
						Logger.minor(this, "Not armed");
					return;
				}
				if (!isReadyToDeployUpdate(false)) {
					if (logMINOR)
						Logger.minor(this, "Not ready to deploy update");
					return;
				}
				if (isDeployingUpdate) {
					if (logMINOR)
						Logger.minor(this, "Already deploying update");
					return;
				}
				started = true;
				isDeployingUpdate = true;
				deps = latestMainJarDependencies;
			}

			success = innerDeployUpdate(deps);
			// isDeployingUpdate remains true as we are about to restart.
		} catch (Throwable t) {
			Logger.error(this, "DEPLOYING UPDATE FAILED: "+t, t);
			System.err.println("UPDATE FAILED: CAUGHT "+t);
			System.err.println("YOUR NODE DID NOT UPDATE. THIS IS PROBABLY A BUG OR SERIOUS PROBLEM SUCH AS OUT OF MEMORY.");
			System.err.println("Cause of the problem: "+t);
			t.printStackTrace();
			failUpdate(t.getMessage());
			String error = l10n("updateFailedInternalError", "reason", t.getMessage());
			node.clientCore.alerts.register(new SimpleUserAlert(false,
					error, error, error, UserAlert.CRITICAL_ERROR));
		} finally {
			if(started && !success) {
				Bucket toFree = null;
				synchronized (this) {
					isDeployingUpdate = false;
					if (maybeNextMainJarVersion > fetchedMainJarVersion) {
						// A newer version has been fetched in the meantime.
						toFree = fetchedMainJarData;
						fetchedMainJarVersion = maybeNextMainJarVersion;
						fetchedMainJarData = maybeNextMainJarData;
						maybeNextMainJarVersion = -1;
						maybeNextMainJarData = null;
					}
				}
				if (toFree != null)
					toFree.free();
			}
		}
	}

	/**
	 * Deploy the update. Inner method. Doesn't check anything, just does it.
	 */
	private boolean innerDeployUpdate(MainJarDependencies deps) {
		System.err.println("Deploying update "+deps.build+" with "+deps.dependencies.size()+" dependencies...");
		// Write the jars, config etc.
		// Then restart

		UpdateDeployContext ctx;
		try {
			ctx = new UpdateDeployContext(deps);
		} catch (UpdaterParserException e) {
			failUpdate("Could not determine which jars are in use: "
					+ e.getMessage());
			return false;
		}

		if (writeJars(ctx, deps)) {
			restart(ctx);
			return true;
		} else {
			if (logMINOR)
				Logger.minor(this, "Did not write jars");
			return false;
		}
	}

	/**
	 * Write the updated jars, if necessary rewrite the wrapper.conf.
	 * 
	 * @return True if this part of the update succeeded.
	 */
	private boolean writeJars(UpdateDeployContext ctx, MainJarDependencies deps) {
		/**
		 * What do we want to do here? 1. If we have a new main jar: - If on
		 * Windows, write it to a new jar file, update the wrapper.conf to point
		 * to it. - Otherwise, write to a new jar file, then move the new jar
		 * file over the old jar file. 2. If the dependencies have changed, we
		 * need to update wrapper.conf.
		 */

		boolean writtenNewJar = false;

		boolean tryEasyWay = File.pathSeparatorChar == ':'
				&& (!deps.mustRewriteWrapperConf);

		if (hasNewMainJar) {
			File mainJar = ctx.getMainJar();
			File newMainJar = ctx.getNewMainJar();
			try {
				if (writeJar(mainJar, newMainJar, mainUpdater, "main",
						tryEasyWay))
					writtenNewJar = true;
			} catch (UpdateFailedException e) {
				failUpdate(e.getMessage());
				return false;
			}
		}

		// Dependencies have been written for us already.
		// But we may need to modify wrapper.conf.

		if (!(writtenNewJar || deps.mustRewriteWrapperConf))
			return true;
		try {
			ctx.rewriteWrapperConf(writtenNewJar);
		} catch (IOException e) {
			failUpdate("Cannot rewrite wrapper.conf: " + e);
			return false;
		} catch (UpdateCatastropheException e) {
			failUpdate(e.getMessage());
			node.clientCore.alerts.register(new SimpleUserAlert(false,
					l10n("updateCatastropheTitle"), e.getMessage(),
					l10n("updateCatastropheTitle"), UserAlert.CRITICAL_ERROR));
			return false;
		} catch (UpdaterParserException e) {
			node.clientCore.alerts.register(new SimpleUserAlert(false,
					l10n("updateFailedTitle"), e.getMessage(), l10n(
							"updateFailedShort", "reason", e.getMessage()),
					UserAlert.CRITICAL_ERROR));
			return false;
		}

		return true;
	}

	/**
	 * Write a jar. Returns true if the caller needs to rewrite the config,
	 * false if he doesn't, or throws if it fails.
	 * 
	 * @param mainJar
	 *            The location of the current jar file.
	 * @param newMainJar
	 *            The location of the new jar file.
	 * @param mainUpdater
	 *            The NodeUpdater for the file in question, so we can ask it to
	 *            write the file.
	 * @param name
	 *            The name of the jar for logging.
	 * @param tryEasyWay
	 *            If true, attempt to rename the new file directly over the old
	 *            one. This avoids the need to rewrite the wrapper config file.
	 * @return True if the caller needs to rewrite the config, false if he
	 *         doesn't (because easy way worked).
	 * @throws UpdateFailedException
	 *             If something breaks.
	 */
	private boolean writeJar(File mainJar, File newMainJar,
			NodeUpdater mainUpdater, String name, boolean tryEasyWay)
			throws UpdateFailedException {
		boolean writtenToTempFile = false;
		try {
			if (newMainJar.exists()) {
				if (!newMainJar.delete()) {
					if (newMainJar.exists()) {
						System.err
								.println("Cannot write to preferred new jar location "
										+ newMainJar);
						if (tryEasyWay) {
							try {
								newMainJar = File.createTempFile("freenet",
										".jar", mainJar.getParentFile());
							} catch (IOException e) {
								throw new UpdateFailedException(
										"Cannot write to any other location either - disk full? "
												+ e);
							}
							// Try writing to it
							try {
								writeJarTo(newMainJar);
								writtenToTempFile = true;
							} catch (IOException e) {
								newMainJar.delete();
								throw new UpdateFailedException(
										"Cannot write new jar - disk full? "
												+ e);
							}
						} else {
							// Try writing it to the new one even though we
							// can't delete it.
							writeJarTo(newMainJar);
						}
					} else {
						writeJarTo(newMainJar);
					}
				} else {
					if (logMINOR)
						Logger.minor(NodeUpdateManager.class,
								"Deleted old jar " + newMainJar);
					writeJarTo(newMainJar);
				}
			} else {
				writeJarTo(newMainJar);
			}
		} catch (IOException e) {
			throw new UpdateFailedException("Cannot update: Cannot write to "
					+ (tryEasyWay ? " temp file " : "new jar ") + newMainJar);
		}

		if (tryEasyWay) {
			// Do it the easy way. Just rewrite the main jar.
			if (!newMainJar.renameTo(mainJar)) {
				Logger.error(NodeUpdateManager.class,
						"Cannot rename temp file " + newMainJar
								+ " over original jar " + mainJar);
				if (writtenToTempFile) {
					// Fail the update - otherwise we will leak disk space
					newMainJar.delete();
					throw new UpdateFailedException(
							"Cannot write to preferred new jar location and cannot rename temp file over old jar, update failed");
				}
				// Try the hard way
			} else {
				System.err.println("Written new Freenet jar.");
				return false;
			}
		}
		return true;
	}

	public void writeJarTo(File fNew) throws IOException {
		if (!fNew.delete() && fNew.exists()) {
			System.err.println("Can't delete " + fNew + "!");
		}

		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(fNew);

			BucketTools.copyTo(this.fetchedMainJarData, fos, -1);

			fos.flush();
		} finally {
			Closer.close(fos);
		}
	}

	@SuppressWarnings("serial")
	private static class UpdateFailedException extends Exception {

		public UpdateFailedException(String message) {
			super(message);
		}

	}

	/** Restart the node. Does not return. */
	private void restart(UpdateDeployContext ctx) {
		if (logMINOR)
			Logger.minor(this, "Restarting...");
		node.getNodeStarter().restart();
		try {
			Thread.sleep(5 * 60 * 1000);
		} catch (InterruptedException e) {
			// Break
		} // in case it's still restarting
		System.err
				.println("Failed to restart. Exiting, please restart the node.");
		System.exit(NodeInitException.EXIT_RESTART_FAILED);
	}

	private void failUpdate(String reason) {
		Logger.error(this, "Update failed: " + reason);
		System.err.println("Update failed: " + reason);
		this.killUpdateAlerts();
		node.clientCore.alerts.register(new SimpleUserAlert(true,
				l10n("updateFailedTitle"), l10n("updateFailed", "reason",
						reason), l10n("updateFailedShort", "reason", reason),
				UserAlert.CRITICAL_ERROR));
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("NodeUpdateManager." + key);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("NodeUpdateManager." + key,
				pattern, value);
	}

	/**
	 * Called when a new jar has been downloaded. The caller should process the
	 * dependencies *AFTER* this method has completed, and then call
	 * onDependenciesReady().
	 * 
	 * @param fetched
	 *            The build number we have fetched.
	 * @param result
	 *            The actual data.
	 */
	void onDownloadedNewJar(Bucket result, int fetched, File savedBlob) {
		Bucket delete1 = null;
		Bucket delete2 = null;
		synchronized (this) {
			if (fetched > Version.buildNumber()) {
				hasNewMainJar = true;
				startedFetchingNextMainJar = -1;
				gotJarTime = System.currentTimeMillis();
				if (logMINOR)
					Logger.minor(this, "Got main jar: " + fetched);
			}
			if (!isDeployingUpdate) {
				delete1 = fetchedMainJarData;
				fetchedMainJarVersion = fetched;
				fetchedMainJarData = result;
				if(fetched == Version.buildNumber()) {
					if(savedBlob != null)
						currentVersionBlobFile = savedBlob;
					else
						Logger.error(this, "No blob file for latest version?!", new Exception("error"));
				}
			} else {
				delete2 = maybeNextMainJarData;
				maybeNextMainJarVersion = fetched;
				maybeNextMainJarData = result;
				System.out
						.println("Already deploying update, not using new main jar #"
								+ fetched);
			}
		}
		if (delete1 != null)
			delete1.free();
		if (delete2 != null)
			delete2.free();
		// We cannot deploy yet, we must wait for the dependencies check.
	}

	/**
	 * Called when the NodeUpdater starts to fetch a new version of the jar.
	 */
	void onStartFetching() {
		long now = System.currentTimeMillis();
		synchronized (this) {
			startedFetchingNextMainJar = now;
		}
	}

	private boolean disabledNotBlown;

	/**
	 * @param msg
	 * @param disabledNotBlown
	 *            If true, the auto-updating system is broken, and should be
	 *            disabled, but the problem *could* be local e.g. out of disk
	 *            space and a node sends us a revocation certificate.
	 */
	public void blow(String msg, boolean disabledNotBlown) {
		NodeUpdater main, ext;
		synchronized (this) {
			if (hasBeenBlown) {
				if (this.disabledNotBlown && !disabledNotBlown)
					disabledNotBlown = true;
				Logger.error(this,
						"The key has ALREADY been marked as blown! Message was "
								+ revocationMessage + " new message " + msg);
				return;
			} else {
				this.revocationMessage = msg;
				this.hasBeenBlown = true;
				this.disabledNotBlown = disabledNotBlown;
				// We must get to the lower part, and show the user the message
				try {
					if (disabledNotBlown) {
						System.err
								.println("THE AUTO-UPDATING SYSTEM HAS BEEN DISABLED!");
						System.err
								.println("We do not know whether this is a local problem or the auto-update system has in fact been compromised. What we do know:\n"
										+ revocationMessage);
					} else {
						System.err
								.println("THE AUTO-UPDATING SYSTEM HAS BEEN COMPROMISED!");
						System.err
								.println("The auto-updating system revocation key has been inserted. It says: "
										+ revocationMessage);
					}
				} catch (Throwable t) {
					try {
						Logger.error(this, "Caught " + t, t);
					} catch (Throwable t1) {
					}
				}
			}
			main = mainUpdater;
			if (main != null)
				main.preKill();
			mainUpdater = null;
		}
		if (main != null)
			main.kill();
		if (revocationAlert == null) {
			revocationAlert = new RevocationKeyFoundUserAlert(msg,
					disabledNotBlown);
			node.clientCore.alerts.register(revocationAlert);
			// we don't need to advertize updates : we are not going to do them
			killUpdateAlerts();
		}
		uom.killAlert();
		broadcastUOMAnnouncesOld();
		broadcastUOMAnnouncesNew();
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
		broadcastUOMAnnouncesNew();
		node.ticker.queueTimedJob(new Runnable() {
			@Override
			public void run() {
				revocationChecker.start(false);
			}
		}, node.random.nextInt(24 * 60 * 60 * 1000));
	}

	private void deployPluginUpdates() {
		PluginJarUpdater[] updaters = null;
		synchronized (this) {
			if (this.pluginUpdaters != null)
				updaters = pluginUpdaters.values().toArray(
						new PluginJarUpdater[pluginUpdaters.size()]);
		}
		boolean restartRevocationFetcher = false;
		if (updaters != null) {
			for (PluginJarUpdater u : updaters) {
				if (u.onNoRevocation())
					restartRevocationFetcher = true;
			}
		}
		if (restartRevocationFetcher)
			revocationChecker.start(true, true);
	}

	public void arm() {
		armed = true;
		OpennetManager om = node.getOpennet();
		if (om != null) {
			if (om.waitingForUpdater()) {
				synchronized (this) {
					// Reannounce and count it from now.
					if (gotJarTime > 0)
						gotJarTime = System.currentTimeMillis();
				}
				om.reannounce();
			}
		}
		deployOffThread(0, false);
	}

	void deployOffThread(long delay, final boolean announce) {
		node.ticker.queueTimedJob(new Runnable() {
			@Override
			public void run() {
				if(announce)
					maybeBroadcastUOMAnnouncesNew();
				if (logMINOR)
					Logger.minor(this, "Running deployOffThread");
				deployUpdate();
				if (logMINOR)
					Logger.minor(this, "Run deployOffThread");
			}
		}, delay);
	}
	
	protected void maybeBroadcastUOMAnnouncesNew() {
		if(logMINOR) Logger.minor(this, "Maybe broadcast UOM announces new");
		synchronized(NodeUpdateManager.this) {
			if(hasBeenBlown) return;
			if(peersSayBlown) return;
		}
		if(logMINOR) Logger.minor(this, "Maybe broadcast UOM announces new (2)");
		// If the node has no peers, noRevocationFound will never be called.
		broadcastUOMAnnouncesNew();
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

	/**
	 * What version has been fetched?
	 * 
	 * This includes jar's fetched via UOM, because the UOM code feeds its
	 * results through the mainUpdater.
	 */
	public int newMainJarVersion() {
		if (mainUpdater == null)
			return -1;
		return mainUpdater.getFetchedVersion();
	}

	public boolean fetchingNewMainJar() {
		return (mainUpdater != null && mainUpdater.isFetching());
	}

	public int fetchingNewMainJarVersion() {
		if (mainUpdater == null)
			return -1;
		return mainUpdater.fetchingVersion();
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

	/**
	 * Is the node able to update as soon as the revocation fetch has been
	 * completed?
	 */
	public boolean canUpdateNow() {
		return isReadyToDeployUpdate(true);
	}

	/**
	 * Is the node able to update *immediately*? (i.e. not only is it ready in
	 * every other sense, but also a revocation fetch has completed recently
	 * enough not to need another one)
	 */
	public boolean canUpdateImmediately() {
		return isReadyToDeployUpdate(false);
	}

	// Config callbacks

	class UpdaterEnabledCallback extends BooleanCallback {

		@Override
		public Boolean get() {
			if (isEnabled())
				return true;
			synchronized (NodeUpdateManager.this) {
				if (disabledNotBlown)
					return true;
			}
			return false;
		}

		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			enable(val);
		}
	}

	class AutoUpdateAllowedCallback extends BooleanCallback {

		@Override
		public Boolean get() {
			return isAutoUpdateAllowed();
		}

		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			setAutoUpdateAllowed(val);
		}
	}

	class UpdateURICallback extends StringCallback {

		@Override
		public String get() {
			return getURI().toString(false, false);
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			FreenetURI uri;
			try {
				uri = new FreenetURI(val);
			} catch (MalformedURLException e) {
				throw new InvalidConfigValueException(l10n(
						"invalidUpdateURI", "error",
						e.getLocalizedMessage()));
			}
			if(updateURI.hasMetaStrings())
				throw new InvalidConfigValueException(l10n("updateURIMustHaveNoMetaStrings"));
			if(!updateURI.isUSK())
				throw new InvalidConfigValueException(l10n("updateURIMustBeAUSK"));
			setURI(uri);
		}
	}

	public class UpdateRevocationURICallback extends StringCallback {

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
				throw new InvalidConfigValueException(l10n(
						"invalidRevocationURI", "error",
						e.getLocalizedMessage()));
			}
			setRevocationURI(uri);
		}
	}

	/**
	 * Called when a peer indicates in its UOMAnnounce that it has fetched the
	 * revocation key (or failed to do so in a way suggesting that somebody
	 * knows the key).
	 * 
	 * @param source
	 *            The node which is claiming this.
	 */
	void peerClaimsKeyBlown() {
		// Note that UpdateOverMandatoryManager manages the list of peers who
		// think this.
		// All we have to do is cancel the update.

		peersSayBlown = true;
	}

	/** Called inside locks, so don't lock anything */
	public void notPeerClaimsKeyBlown() {
		peersSayBlown = false;
		node.executor.execute(new Runnable() {

			@Override
			public void run() {
				isReadyToDeployUpdate(false);
			}

		}, "Check for updates");
		node.getTicker().queueTimedJob(new Runnable() {

			@Override
			public void run() {
				maybeBroadcastUOMAnnouncesNew();
			}
			
		}, REVOCATION_FETCH_TIMEOUT);
	}

	boolean peersSayBlown() {
		return peersSayBlown;
	}

	public File getMainBlob(int version) {
		NodeUpdater updater;
		synchronized (this) {
			if (hasBeenBlown)
				return null;
			updater = mainUpdater;
			if (updater == null)
				return null;
		}
		return updater.getBlobFile(version);
	}

	public synchronized long timeRemainingOnCheck() {
		long now = System.currentTimeMillis();
		return Math.max(0, REVOCATION_FETCH_TIMEOUT - (now - gotJarTime));
	}

	final ByteCounter ctr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			// FIXME
		}

		@Override
		public void sentBytes(int x) {
			node.nodeStats.reportUOMBytesSent(x);
		}

		@Override
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

	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing NodeUpdateManager in database",
				new Exception("error"));
		return false;
	}

	public void disconnected(PeerNode pn) {
		uom.disconnected(pn);
	}

	public void deployPlugin(String fn) throws IOException {
		PluginJarUpdater updater;
		synchronized (this) {
			if (hasBeenBlown) {
				Logger.error(this, "Not deploying update for " + fn
						+ " because revocation key has been blown!");
				return;
			}
			updater = pluginUpdaters.get(fn);
		}
		updater.writeJar();
	}

	public void deployPluginWhenReady(String fn) throws IOException {
		PluginJarUpdater updater;
		synchronized (this) {
			if (hasBeenBlown) {
				Logger.error(this, "Not deploying update for " + fn
						+ " because revocation key has been blown!");
				return;
			}
			updater = pluginUpdaters.get(fn);
		}
		boolean wasRunning = revocationChecker.start(true, true);
		updater.arm(wasRunning);
	}

	public boolean dontAllowUOM() {
		if (node.isOpennetEnabled() && node.wantAnonAuth(true)) {
			// We are a seednode.
			// Normally this means we won't send UOM.
			// However, if something breaks severely, we need an escape route.
			if (node.getUptime() > 5 * 60 * 1000
					&& node.peers.countCompatibleRealPeers() == 0)
				return false;
			return true;
		}
		return false;
	}

	public boolean fetchingFromUOM() {
		return uom.isFetchingMain();
	}

	/**
	 * Called when the dependencies have been verified and/or downloaded, and we
	 * can upgrade to the new build without dependency issues.
	 * 
	 * @param deps
	 *            The dependencies object. Used to rewrite wrapper.conf if
	 *            necessary. Also contains the build number.
	 * @param binaryBlob
	 *            The binary blob for this build, including the dependencies.
	 */
	public void onDependenciesReady(MainJarDependencies deps) {
		synchronized (this) {
			this.latestMainJarDependencies = deps;
			this.dependenciesValidForBuild = deps.build;
		}
		revocationChecker.start(true);
		deployOffThread(REVOCATION_FETCH_TIMEOUT, true);
	}

	public File getTransitionExtBlob() {
		return transitionExtJarFetcher.getBlobFile();
	}

	public File getTransitionMainBlob() {
		return transitionMainJarFetcher.getBlobFile();
	}

	/** Show the progress of individual dependencies if possible */
	public void renderProgress(HTMLNode alertNode) {
		MainJarUpdater m;
		synchronized (this) {
			if(this.fetchedMainJarData == null) return;
			m = mainUpdater;
			if(m == null) return;
		}
		m.renderProperties(alertNode);
	}
	
	public boolean brokenDependencies() {
		MainJarUpdater m;
		synchronized (this) {
			m = mainUpdater;
			if(m == null) return false;
		}
		return m.brokenDependencies();
	}

	public void onStartFetchingUOM() {
		MainJarUpdater m;
		synchronized (this) {
			m = mainUpdater;
			if(m == null) return;
		}
		m.onStartFetchingUOM();
	}

	public synchronized File getCurrentVersionBlobFile() {
		if(hasNewMainJar) return null;
		if(isDeployingUpdate) return null;
		if(fetchedMainJarVersion != Version.buildNumber()) return null;
		return currentVersionBlobFile;
	}

	MainJarUpdater getMainUpdater() {
		return mainUpdater;
	}

}
