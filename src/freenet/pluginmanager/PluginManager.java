/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.fcp.ClientPut;
import freenet.clients.http.PageMaker.THEME;
import freenet.clients.http.QueueToadlet;
import freenet.clients.http.Toadlet;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.SubConfig;
import freenet.crypt.SHA256;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestClientBuilder;
import freenet.node.RequestStarter;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.OfficialPlugins.OfficialPluginDescription;
import freenet.pluginmanager.PluginManager.PluginProgress.ProgressState;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.JarClassLoader;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SerialExecutor;
import freenet.support.Ticker;
import freenet.support.api.BooleanCallback;
import freenet.support.api.HTTPRequest;
import freenet.support.api.StringArrCallback;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.io.NativeThread.PriorityLevel;

public class PluginManager {

	private final HashMap<String, FredPlugin> toadletList = new HashMap<String, FredPlugin>();

	/* All currently starting plugins. */
	private final OfficialPlugins officialPlugins = new OfficialPlugins();
	private final LoadedPlugins loadedPlugins = new LoadedPlugins();
	final Node node;
	private final NodeClientCore core;
	private boolean logMINOR;
	private boolean logDEBUG;
	private final HighLevelSimpleClient client;

	private static PluginManager selfinstance = null;

	private THEME fproxyTheme;

	private final SerialExecutor executor;

	private boolean alwaysLoadOfficialPluginsFromCentralServer = false;

	static final short PRIO = RequestStarter.INTERACTIVE_PRIORITY_CLASS;
	/** Is the plugin system enabled? Set at boot time only. Mainly for simulations. */
	private final boolean enabled;

	public PluginManager(Node node, int lastVersion) {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
		// config

		this.node = node;
		this.core = node.clientCore;

		if(logMINOR)
			Logger.minor(this, "Starting Plugin Manager");

		if(logDEBUG)
			Logger.debug(this, "Initialize Plugin Manager config");

		client = core.makeClient(PRIO, true, false);

		// callback executor
		executor = new SerialExecutor(PriorityLevel.NORM_PRIORITY.value);
		executor.start(node.executor, "PM callback executor");

        SubConfig pmconfig = node.config.createSubConfig("pluginmanager");
        pmconfig.register("enabled", true, 0, true, true, "PluginManager.enabled", "PluginManager.enabledLong", new BooleanCallback() {

            @Override
            public synchronized Boolean get() {
                return enabled;
            }

            @Override
            public void set(Boolean val) throws InvalidConfigValueException,
                    NodeNeedRestartException {
                if(enabled != val)
                    throw new NodeNeedRestartException(l10n("changePluginManagerEnabledInConfig"));
            }
		    
		});
		enabled = pmconfig.getBoolean("enabled");
		
		// Start plugins in the config
		pmconfig.register("loadplugin", null, 0, true, false, "PluginManager.loadedOnStartup", "PluginManager.loadedOnStartupLong",
			new StringArrCallback() {

				@Override
				public String[] get() {
					return getConfigLoadString();
				}

				@Override
				public void set(String[] val) throws InvalidConfigValueException {
					//if(storeDir.equals(new File(val))) return;
					// FIXME
					throw new InvalidConfigValueException(NodeL10n.getBase().getString("PluginManager.cannotSetOnceLoaded"));
				}

			@Override
				public boolean isReadOnly() {
					return true;
				}
			});

		toStart = pmconfig.getStringArr("loadplugin");

		if(lastVersion < 1237 && contains(toStart, "XMLLibrarian") && !contains(toStart, "Library")) {
			toStart = Arrays.copyOf(toStart, toStart.length+1);
			toStart[toStart.length-1] = "Library";
			System.err.println("Loading Library plugin, replaces XMLLibrarian, when upgrading from pre-1237");
		}

		if(contains(toStart, "KeyExplorer")) {
			for(int i=0;i<toStart.length;i++) {
				if("KeyExplorer".equals(toStart[i]))
					toStart[i] = "KeyUtils";
			}
			System.err.println("KeyExplorer plugin renamed to KeyUtils");
		}

		// This should default to false. Even though we use SSL, a wiretapper may be able to tell which
		// plugin is being loaded, and correlate that with identity creation; plus of course they can see
		// that somebody is using Freenet.
		pmconfig.register("alwaysLoadOfficialPluginsFromCentralServer", false, 0, false, false, "PluginManager.alwaysLoadPluginsFromCentralServer", "PluginManager.alwaysLoadPluginsFromCentralServerLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return alwaysLoadOfficialPluginsFromCentralServer;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException, NodeNeedRestartException {
				alwaysLoadOfficialPluginsFromCentralServer = val;
			}

		});

		alwaysLoadOfficialPluginsFromCentralServer = pmconfig.getBoolean("alwaysLoadOfficialPluginsFromCentralServer");
		if (lastVersion <= 1437) {
			// Overwrite this setting, since it will have been set by the old callback and then written as it's not default.
			// FIXME remove back compatibility code.
			alwaysLoadOfficialPluginsFromCentralServer = false;
		}

		pmconfig.finishedInitialization();

		fproxyTheme = THEME.themeFromName(node.config.get("fproxy").getString("css"));
		selfinstance = this;
	}

	private boolean contains(String[] array, String string) {
		for(String s : array)
			if(string.equals(s)) return true;
		return false;
	}

	private boolean started;
	private boolean stopping;
	private String[] toStart;

	public void start() {
		if (!enabled) return;
		synchronized (loadedPlugins) {
			if (started) {
				return;
			}
		}

		final Semaphore startingPlugins = new Semaphore(0);
			for(final String name : toStart) {
			    core.getExecutor().execute(new Runnable() {

                    @Override
                    public void run() {
                        startPluginAuto(name, false);
                        startingPlugins.release();
                    }
			        
			    });
			}

		core.getExecutor().execute(new Runnable() {
			@Override
			public void run() {
				startingPlugins.acquireUninterruptibly(toStart.length);
				synchronized (loadedPlugins) {
					started = true;
					toStart = null;
				}
			}
		});
	}

	public void stop(long maxWaitTime) {
	    if(!enabled) return;
		// Stop loading plugins.
		synchronized (loadedPlugins) {
			stopping = true;
		}
		for (PluginProgress progress : loadedPlugins.getStartingPlugins()) {
			progress.kill();
		}
		// Stop already loaded plugins.
		for (PluginInfoWrapper pi : loadedPlugins.getLoadedPlugins()) {
			pi.startShutdownPlugin(this, false);
		}
		long now = System.currentTimeMillis();
		long deadline = now + maxWaitTime;
		while(true) {
			int delta = (int) (deadline - now);
			if(delta <= 0) {
				String list = pluginList(loadedPlugins.getLoadedPlugins());
				Logger.error(this, "Plugins still shutting down at timeout:\n"+list);
				System.err.println("Plugins still shutting down at timeout:\n"+list);
			} else {
				for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
					System.out.println("Waiting for plugin to finish shutting down: " + pluginInfoWrapper.getFilename());
					if (pluginInfoWrapper.finishShutdownPlugin(this, delta, false)) {
						loadedPlugins.removeLoadedPlugin(pluginInfoWrapper);
					}
				}
				if (!loadedPlugins.hasLoadedPlugins()) {
					Logger.normal(this, "All plugins unloaded");
					System.out.println("All plugins unloaded");
					return;
				}
				String list = pluginList(loadedPlugins.getLoadedPlugins());
				Logger.error(this, "Plugins still shutting down:\n"+list);
				System.err.println("Plugins still shutting down:\n"+list);
			}
		}
	}

	private static String pluginList(Collection<PluginInfoWrapper> wrappers) {
		StringBuffer sb = new StringBuffer();
		for(PluginInfoWrapper pi : wrappers) {
			sb.append(pi.getFilename());
			sb.append('\n');
		}
		return sb.toString();
	}

	private String[] getConfigLoadString() {
		synchronized (loadedPlugins) {
			if (!started) {
				return toStart;
			}
		}
		List<String> v = new ArrayList<String>();
		for (PluginInfoWrapper pi : loadedPlugins.getLoadedPlugins()) {
			v.add(pi.getFilename());
		}
		v.addAll(loadedPlugins.getFailedPluginNames());
		return v.toArray(new String[v.size()]);
	}

	/**
	 * Returns a set of all currently starting plugins.
	 *
	 * @return All currently starting plugins
	 */
	public Set<PluginProgress> getStartingPlugins() {
		return new HashSet<PluginProgress>(loadedPlugins.getStartingPlugins());
	}

	// try to guess around...
	public PluginInfoWrapper startPluginAuto(final String pluginname, boolean store) {

		OfficialPluginDescription desc;
		if((desc = isOfficialPlugin(pluginname)) != null) {
			return startPluginOfficial(pluginname, store, desc, false, false);
		}

		try {
			new FreenetURI(pluginname); // test for MalformedURLException
			return startPluginFreenet(pluginname, store);
		} catch(MalformedURLException e) {
			// not a freenet key
		}

		File[] roots = File.listRoots();
		for(File f : roots) {
			if(pluginname.startsWith(f.getName()) && new File(pluginname).exists()) {
				return startPluginFile(pluginname, store);
			}
		}

		return startPluginURL(pluginname, store);
	}

	public PluginInfoWrapper startPluginOfficial(final String pluginname, boolean store, boolean force, boolean forceHTTPS) {
		return startPluginOfficial(pluginname, store, officialPlugins.get(pluginname), force, forceHTTPS);
	}

	public PluginInfoWrapper startPluginOfficial(final String pluginname, boolean store, OfficialPluginDescription desc, boolean force, boolean forceHTTPS) {
		if((alwaysLoadOfficialPluginsFromCentralServer && !force)|| force && forceHTTPS) {
			return realStartPlugin(new PluginDownLoaderOfficialHTTPS(), pluginname, store,
				desc.alwaysFetchLatestVersion);
		} else {
			return realStartPlugin(new PluginDownLoaderOfficialFreenet(client, node, false),
				pluginname, store, desc.alwaysFetchLatestVersion);
		}
	}

	public PluginInfoWrapper startPluginFile(final String filename, boolean store) {
		return realStartPlugin(new PluginDownLoaderFile(), filename, store, false);
	}

	public PluginInfoWrapper startPluginURL(final String filename, boolean store) {
		return realStartPlugin(new PluginDownLoaderURL(), filename, store, false);
	}

	public PluginInfoWrapper startPluginFreenet(final String filename, boolean store) {
		return realStartPlugin(new PluginDownLoaderFreenet(client, node, false), filename, store, false);
	}

	private PluginInfoWrapper realStartPlugin(final PluginDownLoader<?> pdl, final String filename, final boolean store, boolean alwaysDownload) {
	    if (!enabled) throw new IllegalStateException("Plugins disabled");
		if(filename.trim().length() == 0)
			return null;
		final PluginProgress pluginProgress = new PluginProgress(filename, pdl);
		loadedPlugins.addStartingPlugin(pluginProgress);
		Logger.normal(this, "Loading plugin: " + filename);
		FredPlugin plug;
		PluginInfoWrapper pi = null;
		try {
			plug = loadPlugin(pdl, filename, pluginProgress, alwaysDownload);
			pluginProgress.setProgress(ProgressState.STARTING);
			pi = new PluginInfoWrapper(node, plug, filename, pdl.isOfficialPluginLoader());
			PluginHandler.startPlugin(PluginManager.this, pi);
			loadedPlugins.addLoadedPlugin(pi);
			loadedPlugins.removeFailedPlugin(filename);
			Logger.normal(this, "Plugin loaded: " + filename);
		} catch (PluginAlreadyLoaded e) {
			return null;
		} catch (PluginNotFoundException e) {
			Logger.normal(this, "Loading plugin failed (" + filename + ')', e);
			boolean stillTrying = false;
			if (pdl.isLoadingFromFreenet()) {
				PluginDownLoaderFreenet downloader = (PluginDownLoaderFreenet) pdl;
				if (!downloader.fatalFailure() && !downloader.desperate && !twoCopiesInStartingPlugins(filename)) {
					// Retry forever...
					final PluginDownLoader<?> retry = pdl.getRetryDownloader();
					stillTrying = true;
					node.getTicker().queueTimedJob(new Runnable() {

						@Override
						public void run() {
							realStartPlugin(retry, filename, store, true);
						}

					}, 0);
				}
			}
			PluginLoadFailedUserAlert newAlert =
				new PluginLoadFailedUserAlert(filename, pdl.isOfficialPluginLoader(), pdl.isOfficialPluginLoader() && pdl.isLoadingFromFreenet(), stillTrying, e);
			PluginLoadFailedUserAlert oldAlert = loadedPlugins.replaceUserAlert(filename, newAlert);
			core.alerts.register(newAlert);
			core.alerts.unregister(oldAlert);
		} catch (UnsupportedClassVersionError e) {
			Logger.error(this, "Could not load plugin " + filename + " : " + e,
					e);
			System.err.println("Could not load plugin " + filename + " : " + e);
			e.printStackTrace();
			System.err.println("Plugin " + filename + " appears to require a later JVM");
			Logger.error(this, "Plugin " + filename + " appears to require a later JVM");
			PluginLoadFailedUserAlert newAlert =
				new PluginLoadFailedUserAlert(filename, pdl.isOfficialPluginLoader(), pdl.isOfficialPluginLoader() && pdl.isLoadingFromFreenet(), false, l10n("pluginReqNewerJVMTitle", "name", filename));
			PluginLoadFailedUserAlert oldAlert = loadedPlugins.replaceUserAlert(filename, newAlert);
			core.alerts.register(newAlert);
			core.alerts.unregister(oldAlert);
		} catch (Throwable e) {
			Logger.error(this, "Could not load plugin " + filename + " : " + e, e);
			System.err.println("Could not load plugin " + filename + " : " + e);
			e.printStackTrace();
			System.err.println("Plugin "+filename+" is broken, but we want to retry after next startup");
			Logger.error(this, "Plugin "+filename+" is broken, but we want to retry after next startup");
			PluginLoadFailedUserAlert newAlert =
				new PluginLoadFailedUserAlert(filename, pdl.isOfficialPluginLoader(), pdl.isOfficialPluginLoader() && pdl.isLoadingFromFreenet(), false, e);
			PluginLoadFailedUserAlert oldAlert = loadedPlugins.replaceUserAlert(filename, newAlert);
			core.alerts.register(newAlert);
			core.alerts.unregister(oldAlert);
		} finally {
			loadedPlugins.removeStartingPlugin(pluginProgress);
		}
		/* try not to destroy the config. */
		synchronized(this) {
			if (store)
				core.storeConfig();
		}
		if(pi != null)
			node.nodeUpdater.startPluginUpdater(filename);
		return pi;
	}

	private synchronized boolean twoCopiesInStartingPlugins(String filename) {
		int count = 0;
		for (PluginProgress progress : loadedPlugins.getStartingPlugins()) {
			if(filename.equals(progress.name)) {
				count++;
				if(count == 2) return true;
			}
		}
		return false;
	}

	class PluginLoadFailedUserAlert extends AbstractUserAlert {

		final String filename;
		final String message;
		final StackTraceElement[] stacktrace;
		final boolean official;
		final boolean officialFromFreenet;
		final boolean stillTryingOverFreenet;

		public PluginLoadFailedUserAlert(String filename, boolean official, boolean officialFromFreenet, boolean stillTryingOverFreenet, String message) {
			this.filename = filename;
			this.official = official;
			this.message = message;
			this.stacktrace = null;
			this.officialFromFreenet = officialFromFreenet;
			this.stillTryingOverFreenet = stillTryingOverFreenet;
		}

		public PluginLoadFailedUserAlert(String filename, boolean official, boolean officialFromFreenet, boolean stillTryingOverFreenet, Throwable e) {
			this.filename = filename;
			this.official = official;
			this.stillTryingOverFreenet = stillTryingOverFreenet;
			String msg;
			if(e instanceof PluginNotFoundException) {
				msg = e.getMessage();
				stacktrace = null;
			} else {
				// If it's something wierd, we need to know what it is.
				msg = e.getClass() + ": " + e.getMessage();
				stacktrace = e.getStackTrace();
			}
			if(msg == null) msg = e.toString();
			this.message = msg;
			this.officialFromFreenet = officialFromFreenet;
		}

		@Override
		public String dismissButtonText() {
			return l10n("deleteFailedPluginButton");
		}

		@Override
		public void onDismiss() {
			loadedPlugins.removeFailedPlugin(filename);
			node.executor.execute(new Runnable() {

				@Override
				public void run() {
					cancelRunningLoads(filename, null);
				}

			});
		}

		@Override
		public String anchor() {
			return "pluginfailed:"+filename;
		}

		@Override
		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");
			HTMLNode p = div.addChild("p");
			p.addChild("#", l10n("pluginLoadingFailedWithMessage", new String[] { "name", "message" }, new String[] { filename, message }));

			if(stacktrace != null) {
				for(StackTraceElement e : stacktrace) {
					p.addChild("br");
					p.addChild("%", "&nbsp; &nbsp; &nbsp; &nbsp;");
					p.addChild("#", "at " + e);
				}
			}

			if(stillTryingOverFreenet) {
				div.addChild("p", l10n("pluginLoadingFailedStillTryingOverFreenet"));
			}

			if(official) {
				p = div.addChild("p");
				if(officialFromFreenet)
					p.addChild("#", l10n("officialPluginLoadFailedSuggestTryAgainFreenet"));
				else
					p.addChild("#", l10n("officialPluginLoadFailedSuggestTryAgainHTTPS"));

				HTMLNode reloadForm = div.addChild("form", new String[] { "action", "method" }, new String[] { "/plugins/", "post" });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "plugin-name", filename });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "pluginSource", "https" });
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-official", l10n("officialPluginLoadFailedTryAgain") });

				if(!stillTryingOverFreenet) {
					reloadForm = div.addChild("form", new String[] { "action", "method" }, new String[] { "/plugins/", "post" });
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.clientCore.formPassword });
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "plugin-name", filename });
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "pluginSource", "freenet" });
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-official", l10n("officialPluginLoadFailedTryAgainFreenet") });
				}
			}

			return div;
		}

		@Override
		public short getPriorityClass() {
			return UserAlert.ERROR;
		}

		@Override
		public String getShortText() {
			return l10n("pluginLoadingFailedShort", "name", filename);
		}

		@Override
		public String getText() {
			return l10n("pluginLoadingFailedWithMessage", new String[] { "name", "message" }, new String[] { filename, message });
		}

		@Override
		public String getTitle() {
			return l10n("pluginLoadingFailedTitle");
		}

		@Override
		public boolean isEventNotification() {
			return false;
		}

		@Override
		public boolean isValid() {
			boolean success = loadedPlugins.isFailedPlugin(filename);
			if(!success) {
				core.alerts.unregister(this);
			}
			return success;
		}

		@Override
		public void isValid(boolean validity) {
		}

		@Override
		public boolean shouldUnregisterOnDismiss() {
			return true;
		}

		@Override
		public boolean userCanDismiss() {
			return true;
		}


	}

	void register(PluginInfoWrapper pi) {
		FredPlugin plug = pi.getPlugin();

		// handles FProxy? If so, register
		if(pi.isPproxyPlugin())
			registerToadlet(plug);

		if(pi.isConfigurablePlugin()) {
			// Registering the toadlet with atFront=false means that
			// the node's ConfigToadlet will clobber the plugin's
			// ConfigToadlet and the page will not be visible. So it
			// must be registered with atFront=true. This means that
			// malicious plugins could try to hijack node config
			// pages, to ill effect. Let's avoid that.
			boolean pluginIsTryingToHijackNodeConfig = false;
			for(SubConfig subconfig : node.config.getConfigs()) {
				if(pi.getPluginClassName().equals(subconfig.getPrefix())) {
					pluginIsTryingToHijackNodeConfig = true;
					break;
				}
			}
			if(pluginIsTryingToHijackNodeConfig) {
				Logger.warning(this, "The plugin loaded from "+pi.getFilename()+" is attempting to hijack a node configuration page; refusing to register its ConfigToadlet");
			} else {
				Toadlet toadlet = pi.getConfigToadlet();
				core.getToadletContainer().register(toadlet, "FProxyToadlet.categoryConfig", toadlet.path(), true, "ConfigToadlet."+pi.getPluginClassName()+".label", "ConfigToadlet."+pi.getPluginClassName()+".tooltip", true, null, (FredPluginL10n)pi.getPlugin());
			}
		}

		if(pi.isIPDetectorPlugin())
			node.ipDetector.registerIPDetectorPlugin((FredPluginIPDetector) plug);
		if(pi.isPortForwardPlugin())
			node.ipDetector.registerPortForwardPlugin((FredPluginPortForward) plug);
		if(pi.isBandwidthIndicator())
			node.ipDetector.registerBandwidthIndicatorPlugin((FredPluginBandwidthIndicator) plug);
	}

	public void cancelRunningLoads(String filename, PluginProgress exceptFor) {
		Logger.normal(this, "Cancelling loads for plugin "+filename);
		for (PluginProgress progress : new ArrayList<PluginProgress>(loadedPlugins.getStartingPlugins())) {
			if ((progress != exceptFor) && filename.equals(progress.name)) {
				progress.kill();
				loadedPlugins.removeStartingPlugin(progress);
			}
		}
	}

	/**
	 * Returns the translation of the given key, prefixed by the short name of
	 * the current class.
	 *
	 * @param key
	 *            The key to fetch
	 * @return The translation
	 */
	static String l10n(String key) {
		return NodeL10n.getBase().getString("PluginManager." + key);
	}

	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("PluginManager." + key, pattern, value);
	}

	/**
	 * Returns the translation of the given key, replacing each occurence of
	 * <code>${<em>pattern</em>}</code> with <code>value</code>.
	 *
	 * @param key
	 *            The key to fetch
	 * @param patterns
	 *            The patterns to replace
	 * @param values
	 *            The values to substitute
	 * @return The translation
	 */
	private String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("PluginManager." + key, patterns, values);
	}

	private void registerToadlet(FredPlugin pl) {
		//toadletList.put(e.getStackTrace()[1].getClass().toString(), pl);
		synchronized(toadletList) {
			toadletList.put(pl.getClass().getName(), pl);
		}
		Logger.normal(this, "Added HTTP handler for /plugins/" + pl.getClass().getName() + '/');
	}

	/**
	 * Remove a plugin from the plugin list.
	 */
	public void removePlugin(PluginInfoWrapper pi) {
		synchronized (loadedPlugins) {
			if (!stopping && !loadedPlugins.hasLoadedPlugin(pi)) {
				return;
			}
		}
		loadedPlugins.removeLoadedPlugin(pi);
		core.storeConfig();
	}

	/**
	 * Removes the cached copy of the given plugin from the plugins/ directory.
	 *
	 * @param pluginSpecification
	 *            The plugin specification
	 */
	public void removeCachedCopy(String pluginSpecification) {
		if(pluginSpecification == null) {
			// Will be null if the file for a given plugin can't be found, eg. if it has already been
			// removed. Ignore it since the file isn't there anyway
			Logger.warning(this, "Can't remove null from cache. Ignoring");
			return;
		}

		int lastSlash = pluginSpecification.lastIndexOf('/');
		String pluginFilename;
		if(lastSlash == -1)
			/* Windows, maybe? */
			lastSlash = pluginSpecification.lastIndexOf('\\');
		File pluginDirectory = node.getPluginDir();
		if(lastSlash == -1) {
			/* it's an official plugin or filename without path */
			if (pluginSpecification.toLowerCase().endsWith(".jar"))
				pluginFilename = pluginSpecification;
			else
				pluginFilename = pluginSpecification + ".jar";
		} else
			pluginFilename = pluginSpecification.substring(lastSlash + 1);
		if(logDEBUG)
			Logger.minor(this, "Delete plugin - plugname: " + pluginSpecification + " filename: " + pluginFilename, new Exception("debug"));
		List<File> cachedFiles = getPreviousInstances(pluginDirectory, pluginFilename);
		for (File cachedFile : cachedFiles) {
			if (!cachedFile.delete())
				if(logMINOR) Logger.minor(this, "Can't delete file " + cachedFile);
		}
	}

	public void unregisterPluginToadlet(PluginInfoWrapper pi) {
		synchronized(toadletList) {
			try {
				toadletList.remove(pi.getPluginClassName());
				Logger.normal(this, "Removed HTTP handler for /plugins/" +
					pi.getPluginClassName() + '/', new Exception("debug"));
			} catch(Throwable ex) {
				Logger.error(this, "removing Plugin", ex);
			}
		}
	}

	/**
	 * @deprecated will be removed in version 1473.
     */
	@Deprecated
	public void addToadletSymlinks(PluginInfoWrapper pi) {
		synchronized(toadletList) {
			try {
				String targets[] = pi.getPluginToadletSymlinks();
				if(targets == null)
					return;

				for(String target: targets) {
					toadletList.remove(target);
					Logger.normal(this, "Removed HTTP symlink: " + target +
						" => /plugins/" + pi.getPluginClassName() + '/');
				}
			} catch(Throwable ex) {
				Logger.error(this, "removing Toadlet-link", ex);
			}
		}
	}

	/**
	 * @deprecated will be removed in version 1473.
	 */
	@Deprecated
	public void removeToadletSymlinks(PluginInfoWrapper pi) {
		synchronized(toadletList) {
			String rm = null;
			try {
				String targets[] = pi.getPluginToadletSymlinks();
				if(targets == null)
					return;

				for(String target: targets) {
					rm = target;
					toadletList.remove(target);
					pi.removePluginToadletSymlink(target);
					Logger.normal(this, "Removed HTTP symlink: " + target +
						" => /plugins/" + pi.getPluginClassName() + '/');
				}
			} catch(Throwable ex) {
				Logger.error(this, "removing Toadlet-link: " + rm, ex);
			}
		}
	}

	public String dumpPlugins() {
		StringBuilder out = new StringBuilder();
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			out.append(pluginInfoWrapper.toString()).append('\n');
		}
		return out.toString();
	}

	public Set<PluginInfoWrapper> getPlugins() {
		return new TreeSet<PluginInfoWrapper>(loadedPlugins.getLoadedPlugins());
	}

	/**
     * Look for PluginInfo for a Plugin with given classname or filename.
     * 
	 * @return the PluginInfo or null if not found
     * @deprecated
     *     This function was deprecated because the "or filename" part of the function specification
     *     was NOT documented before it was deprecated. Thus it is possible that legacy callers of
     *     the function did wrongly expect or not expect that. When removing this function, please
     *     review the callers for correctness with regards to that.<br>
     *     You might replace usage of this function with
     *     {@link #getPluginInfoByClassName(String)}.
	 */
    @Deprecated
	public PluginInfoWrapper getPluginInfo(String plugname) {
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			if (pluginInfoWrapper.getPluginClassName().equals(plugname) || pluginInfoWrapper.getFilename().equals(plugname)) {
				return pluginInfoWrapper;
			}
		}
		return null;
	}

    /**
     * @param pluginClassName
     *     The name of the main class of the plugin - that is the class which implements
     *     {@link FredPlugin}.
     * @return
     *     The {@link PluginInfoWrapper} for the plugin with the given class name, or null if no
     *     matching plugin was found.
     */
    public PluginInfoWrapper getPluginInfoByClassName(String pluginClassName) {
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			if (pluginInfoWrapper.getPluginClassName().equals(pluginClassName)) {
				return pluginInfoWrapper;
			}
		}
		return null;
	}

	/**
	 * look for a FCPPlugin with given classname
	 * @param plugname
	 * @return the plugin or null if not found
     * @deprecated
     *     The {@link FredPluginFCP} API, which this returns, was deprecated to be replaced by
     *     {@link FredPluginFCPMessageHandler.ServerSideFCPMessageHandler}. Plugin authors should
     *     implement the new interface instead of the old, and this codepath to support plugins
     *     which implement the old interface should be removed one day. No new code will be needed
     *     then: The code to use the  new interface already exists in its own codepath - the
     *     equivalent function for the new API is {link #getPluginFCPServer(String)}, and it is
     *     already being used automatically for plugins which implement it.
	 */
    @Deprecated
	public FredPluginFCP getFCPPlugin(String plugname) {
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			if (pluginInfoWrapper.isFCPPlugin() && pluginInfoWrapper.getPluginClassName().equals(plugname) && !pluginInfoWrapper.isStopping()) {
				return (FredPluginFCP) pluginInfoWrapper.plug;
			}
		}
		return null;
	}

    /**
     * Get the {@link FredPluginFCPMessageHandler.ServerSideFCPMessageHandler} of the plugin with
     * the given class name.
     * 
     * @param pluginClassName
     *     See {@link #getPluginInfoByClassName(String)}.
     * @throws PluginNotFoundException
     *     If the specified plugin is not loaded or does not provide an FCP server.
     */
    public FredPluginFCPMessageHandler.ServerSideFCPMessageHandler
            getPluginFCPServer(String pluginClassName)
                throws PluginNotFoundException{
        
        PluginInfoWrapper piw = getPluginInfoByClassName(pluginClassName);
        if(piw != null && piw.isFCPServerPlugin()) {
            return piw.getFCPServerPlugin();
        } else {
            throw new PluginNotFoundException(pluginClassName);
        }
    }

	/**
	 * look for a Plugin with given classname
	 * @param plugname
	 * @return the true if not found
	 */
	public boolean isPluginLoaded(String plugname) {
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			if (pluginInfoWrapper.getPluginClassName().equals(plugname) || pluginInfoWrapper.getFilename().equals(plugname)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @param plugname The plugin filename e.g. "Library" for an official plugin.
	 * @return the true if not found
	 */
	public boolean isPluginLoadedOrLoadingOrWantLoad(String plugname) {
		return loadedPlugins.isKnownPlugin(plugname);
	}

	public String handleHTTPGet(String plugin, HTTPRequest request) throws PluginHTTPException {
		FredPlugin handler = null;
		synchronized(toadletList) {
			handler = toadletList.get(plugin);
		}
		if (!(handler instanceof FredPluginHTTP)) {
			throw new NotFoundPluginHTTPException("Plugin not loaded!", "/plugins");
		}

		ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
		ClassLoader pluginClassLoader = handler.getClass().getClassLoader();
		Thread.currentThread().setContextClassLoader(pluginClassLoader);
		try {
			return ((FredPluginHTTP) handler).handleHTTPGet(request);
		} finally {
			Thread.currentThread().setContextClassLoader(oldClassLoader);
		}
	}

	public String handleHTTPPost(String plugin, HTTPRequest request) throws PluginHTTPException {
		FredPlugin handler = null;
		synchronized(toadletList) {
			handler = toadletList.get(plugin);
		}
		if (handler == null)
			throw new NotFoundPluginHTTPException("Plugin '"+plugin+"' not found!", "/plugins");

		ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
		ClassLoader pluginClassLoader = handler.getClass().getClassLoader();
		Thread.currentThread().setContextClassLoader(pluginClassLoader);
		try {
		if(handler instanceof FredPluginHTTP)
			return ((FredPluginHTTP) handler).handleHTTPPost(request);
		} finally {
			Thread.currentThread().setContextClassLoader(oldClassLoader);
		}
		throw new NotFoundPluginHTTPException("Plugin '"+plugin+"' not found!", "/plugins");
	}

	public void killPlugin(String name, long maxWaitTime, boolean reloading) {
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			if (pluginInfoWrapper.getThreadName().equals(name)) {
				pluginInfoWrapper.stopPlugin(this, maxWaitTime, reloading);
				break;
			}
		}
	}

	public void killPluginByFilename(String name, long maxWaitTime, boolean reloading) {
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			if (pluginInfoWrapper.getFilename().equals(name)) {
				pluginInfoWrapper.stopPlugin(this, maxWaitTime, reloading);
				break;
			}
		}
	}

	public void killPluginByClass(String name, final long maxWaitTime) {
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			if (pluginInfoWrapper.getPluginClassName().equals(name)) {
				pluginInfoWrapper.stopPlugin(this, maxWaitTime, false);
				break;
			}
		}
	}

	public void killPlugin(FredPlugin plugin, long maxWaitTime) {
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			if (pluginInfoWrapper.plug == plugin) {
				pluginInfoWrapper.stopPlugin(this, maxWaitTime, false);
				break;
			}
		}
	}

	public OfficialPluginDescription getOfficialPlugin(String name) {
		return officialPlugins.get(name);
	}

	public Collection<OfficialPluginDescription> getOfficialPlugins() {
		return officialPlugins.getAll();
	}

	/**
	 * Returns a list of the names of all available official plugins. Right now
	 * this list is hardcoded but in future we could retrieve this list from emu
	 * or from freenet itself.
	 *
	 * @return A list of all available plugin names
	 */
	public List<OfficialPluginDescription> findAvailablePlugins() {
		List<OfficialPluginDescription> availablePlugins = new ArrayList<OfficialPluginDescription>();
		availablePlugins.addAll(officialPlugins.getAll());
		return availablePlugins;
	}

	public OfficialPluginDescription isOfficialPlugin(String name) {
		if((name == null) || (name.trim().length() == 0))
			return null;
		List<OfficialPluginDescription> availablePlugins = findAvailablePlugins();
		for(OfficialPluginDescription desc : availablePlugins) {
			if(desc.name.equals(name))
				return desc;
		}
		return null;
	}

	/** Separate lock for plugin loading. Don't use (this) as we also use that for
	 * writing the config file, and because we do a lot inside the lock below; it
	 * must not be taken in any other circumstance. */
	private final Object pluginLoadSyncObject = new Object();

	/** All plugin updates are on a single request client. */
	public final RequestClient singleUpdaterRequestClient = new RequestClientBuilder().build();

	public File getPluginFilename(String pluginName) {
		File pluginDirectory = node.getPluginDir();
		if((pluginDirectory.exists() && !pluginDirectory.isDirectory()) || (!pluginDirectory.exists() && !pluginDirectory.mkdirs()))
			return null;
		return new File(pluginDirectory, pluginName + ".jar");
	}

	/**
	 * Tries to load a plugin from the given name. If the name only contains the
	 * name of a plugin it is loaded from the plugin directory, if found,
	 * otherwise it's loaded from the project server. If the name contains a
	 * complete url and the short file already exists in the plugin directory
	 * it's loaded from the plugin directory, otherwise it's retrieved from the
	 * remote server.
	 * @param pdl
	 *
	 * @param name
	 *            The specification of the plugin
	 * @param alwaysDownload If true, always download a new version anyway.
	 * This is especially important on Windows, where we will not usually be
	 * able to delete the file after determining that it is too old.
	 * @return An instanciated object of the plugin
	 * @throws PluginNotFoundException
	 *             If anything goes wrong.
	 * @throws PluginAlreadyLoaded if the plugin is already loaded
	 */
	private FredPlugin loadPlugin(PluginDownLoader<?> pdl, String name, PluginProgress progress, boolean alwaysDownload) throws PluginNotFoundException, PluginAlreadyLoaded {

		pdl.setSource(name);

		File pluginDirectory = getPluginDirectory();

		/* get plugin filename. */
		String filename = pdl.getPluginName(name);
		File pluginFile = getTargetFileForPluginDownload(pluginDirectory, filename, !pdl.isCachingProhibited() && !alwaysDownload);

		boolean downloadWasAttempted = false;
		/* check if file needs to be downloaded. */
		if(logMINOR)
			Logger.minor(this, "plugin file " + pluginFile.getAbsolutePath() + " exists: " + pluginFile.exists()+" downloader "+pdl+" name "+name);
		int RETRIES = 5;
		for (int i = 0; i < RETRIES; i++) {
			if (!pluginFile.exists() || pluginFile.length() == 0) {
				try {
					downloadWasAttempted = true;
					System.err.println("Downloading plugin " + name);
					WrapperManager.signalStarting((int) MINUTES.toMillis(5));
					try {
						downloadPluginFile(pdl, pluginDirectory, pluginFile, progress);
						verifyDigest(pdl, pluginFile);
					} catch (IOException ioe1) {
						Logger.error(this, "could not load plugin", ioe1);
						throw new PluginNotFoundException("could not load plugin: " + ioe1.getMessage(), ioe1);
					}
				} catch (PluginNotFoundException e) {
					if (i < RETRIES - 1) {
						Logger.normal(this, "Failed to load plugin: " + e, e);
						continue;
					} else {
						throw e;
					}
				}
			}

			cancelRunningLoads(name, progress);

			// we do quite a lot inside the lock, use a dedicated one
			synchronized (pluginLoadSyncObject) {
				String pluginMainClassName;
				try {
					pluginMainClassName = verifyJarFileAndGetPluginMainClass(pluginFile);
					FredPlugin object = loadPluginFromJarFile(name, pluginFile, pluginMainClassName, pdl.isOfficialPluginLoader());
					if (object != null) {
						return object;
					}
				} catch (PluginNotFoundException e) {
					Logger.error(this, e.getMessage());
					pluginFile.delete();
					if (!downloadWasAttempted) {
						continue;
					}
					throw e;
				}
			}
		}
		return null;
	}

	private File getPluginDirectory() throws PluginNotFoundException {
		File pluginDirectory = node.getPluginDir();
		if ((pluginDirectory.exists() && !pluginDirectory.isDirectory()) || (!pluginDirectory.exists() && !pluginDirectory.mkdirs())) {
			Logger.error(this, "could not create plugin directory");
			throw new PluginNotFoundException("could not create plugin directory");
		}
		return pluginDirectory;
	}

	private File getTargetFileForPluginDownload(File pluginDirectory, String filename, boolean useCachedFile) {
		List<File> filesInPluginDirectory = getPreviousInstances(pluginDirectory, filename);
		cleanCacheDirectory(filesInPluginDirectory, useCachedFile);
		if (!filesInPluginDirectory.isEmpty() && useCachedFile) {
			return new File(pluginDirectory, filesInPluginDirectory.get(0).getName());
		}
		return new File(pluginDirectory, filename + "-" + System.currentTimeMillis());
	}

	private void cleanCacheDirectory(List<File> filesInPluginDirectory, boolean useCachedFile) {
		if (!useCachedFile) {
			deleteCachedVersions(filesInPluginDirectory);
		} else if (!filesInPluginDirectory.isEmpty()) {
			deleteCachedVersions(filesInPluginDirectory.subList(1, filesInPluginDirectory.size()));
		}
	}

	private void deleteCachedVersions(List<File> filesInPluginDirectory) {
		for (File cachedFile : filesInPluginDirectory) {
			cachedFile.delete();
		}
	}

	private void downloadPluginFile(PluginDownLoader<?> pluginDownLoader, File pluginDirectory, File pluginFile, PluginProgress pluginProgress) throws IOException, PluginNotFoundException {
		File tempPluginFile = File.createTempFile("plugin-", ".jar", pluginDirectory);
		tempPluginFile.deleteOnExit();
		OutputStream pluginOutputStream = null;
		InputStream pluginInputStream = null;
		try {
			pluginOutputStream = new FileOutputStream(tempPluginFile);
			pluginInputStream = pluginDownLoader.getInputStream(pluginProgress);
			FileUtil.copy(pluginInputStream, pluginOutputStream, -1);
		} catch (IOException ioe1) {
			tempPluginFile.delete();
			throw ioe1;
		} finally {
			Closer.close(pluginInputStream);
			Closer.close(pluginOutputStream);
		}
		if (tempPluginFile.length() == 0) {
			throw new PluginNotFoundException("downloaded zero length file");
		}
		if (!FileUtil.renameTo(tempPluginFile, pluginFile)) {
			Logger.error(this, "could not rename temp file to plugin file");
			throw new PluginNotFoundException("could not rename temp file to plugin file");
		}
	}

	private void verifyDigest(PluginDownLoader<?> pluginDownLoader, File pluginFile) throws PluginNotFoundException {
		String digest = pluginDownLoader.getSHA1sum();
		if (digest == null) {
			return;
		}
		String testsum = getFileDigest(pluginFile, "SHA-1");
		if (!(digest.equalsIgnoreCase(testsum))) {
			Logger.error(this, "Checksum verification failed, should be " + digest + " but was " + testsum);
			throw new PluginNotFoundException("Checksum verification failed, should be " + digest + " but was " + testsum);
		}
	}

	private String verifyJarFileAndGetPluginMainClass(File pluginFile) throws PluginNotFoundException, PluginAlreadyLoaded {
		JarFile pluginJarFile = null;
		try {
			pluginJarFile = new JarFile(pluginFile);
			Manifest manifest = pluginJarFile.getManifest();
			if (manifest == null) {
				throw new PluginNotFoundException("could not load manifest from plugin file");
			}
			Attributes mainAttributes = manifest.getMainAttributes();
			if (mainAttributes == null) {
				throw new PluginNotFoundException("manifest does not contain attributes");
			}
			String pluginMainClassName = mainAttributes.getValue("Plugin-Main-Class");
			if (pluginMainClassName == null) {
				throw new PluginNotFoundException("manifest does not contain a Plugin-Main-Class attribute");
			}
			if (isPluginLoaded(pluginMainClassName)) {
				Logger.error(this, "Plugin already loaded: " + pluginFile.getName());
				throw new PluginAlreadyLoaded();
			}
			return pluginMainClassName;
		} catch (IOException ioe1) {
			throw new PluginNotFoundException("error procesesing jar file", ioe1);
		} finally {
			Closer.close(pluginJarFile);
		}
	}

	private FredPlugin loadPluginFromJarFile(String name, File pluginFile, String pluginMainClassName, boolean isOfficialPlugin) throws PluginNotFoundException {
		try {
			JarClassLoader jarClassLoader = new JarClassLoader(pluginFile);
			Class<?> pluginMainClass = jarClassLoader.loadClass(pluginMainClassName);
			Object object = pluginMainClass.newInstance();
			if (!(object instanceof FredPlugin)) {
				throw new PluginNotFoundException("plugin main class is not a plugin");
			}
			if (isOfficialPlugin) {
				verifyPluginVersion(name, jarClassLoader, (FredPlugin) object);
			}
			if (object instanceof FredPluginL10n) {
				((FredPluginL10n) object).setLanguage(NodeL10n.getBase().getSelectedLanguage());
			}
			if (object instanceof FredPluginBaseL10n) {
				((FredPluginBaseL10n) object).setLanguage(NodeL10n.getBase().getSelectedLanguage());
			}
			if (object instanceof FredPluginThemed) {
				((FredPluginThemed) object).setTheme(fproxyTheme);
			}
			return (FredPlugin) object;
		} catch (IOException ioe1) {
			throw new PluginNotFoundException("could not load plugin", ioe1);
		} catch (ClassNotFoundException cnfe1) {
			throw new PluginNotFoundException("could not find plugin class: \"" + cnfe1.getMessage() + "\"", cnfe1);
		} catch (InstantiationException ie1) {
			throw new PluginNotFoundException("could not instantiate plugin", ie1);
		} catch (IllegalAccessException iae1) {
			throw new PluginNotFoundException("could not access plugin main class", iae1);
		} catch (NoClassDefFoundError ncdfe1) {
			throw new PluginNotFoundException("could not find class def, may a missing lib?", ncdfe1);
		} catch (Throwable t) {
			throw new PluginNotFoundException("unexpected error while plugin loading " + t, t);
		}
	}

	private void verifyPluginVersion(String name, JarClassLoader jarClassLoader, FredPlugin plugin) throws PluginTooOldException {
		System.err.println("Loading official plugin " + name);
		// Check the version after loading it!
		// FIXME IMPORTANT Build the version into the manifest. This is actually pretty easy and just involves changing build.xml.
		// We already do similar things elsewhere.

		// Ugh, this is just as messy ... ideas???? Maybe we need to have OS
		// detection and use grep/sed on unix and find on windows???

		OfficialPluginDescription desc = officialPlugins.get(name);

		long minVer = desc.minimumVersion;
		long ver = -1;

		if (minVer != -1) {
			if (plugin instanceof FredPluginRealVersioned) {
				ver = ((FredPluginRealVersioned) plugin).getRealVersion();
			}
		}

		// FIXME l10n the PluginNotFoundException errors.
		if (ver < minVer) {
			System.err.println("Failed to load plugin " + name + " : TOO OLD: need at least version " + minVer + " but is " + ver);
			Logger.error(this, "Failed to load plugin " + name + " : TOO OLD: need at least version " + minVer + " but is " + ver);

			// At this point, the constructor has run, so it's theoretically possible that the plugin has e.g. created some threads.
			// However, it has not been able to use any of the node's services, because we haven't passed it the PluginRespirator.
			// So there is no need to call runPlugin and terminate().
			// And it doesn't matter all that much if the shutdown fails - we won't be able to delete the file on Windows anyway, we're relying on the ignoreOld logic.
			// Plus, this will not cause a leak of more than one fd per plugin, even when it has started threads.
			try {
				jarClassLoader.close();
			} catch (Throwable t) {
				Logger.error(this, "Failed to close jar classloader for plugin: " + t, t);
			}
			throw new PluginTooOldException("plugin too old: need at least version " + minVer + " but is " + ver);
		}
	}

	/**
	 * This returns all existing instances of cached JAR files that start with
	 * the given filename followed by a dash (“-”), sorted numerically by the
	 * appendix, largest (i.e. newest) first.
	 *
	 * @param pluginDirectory
	 *            The plugin cache directory
	 * @param filename
	 *            The name of the JAR file
	 * @return All cached instances
	 */
	private List<File> getPreviousInstances(File pluginDirectory, final String filename) {
		List<File> cachedFiles = Arrays.asList(pluginDirectory.listFiles(new FileFilter() {

			@Override
			public boolean accept(File pathname) {
				return pathname.isFile() && pathname.getName().startsWith(filename);
			}
		}));
		Collections.sort(cachedFiles, new Comparator<File>() {

			@Override
			public int compare(File file1, File file2) {
				return (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, extractTimestamp(file2.getName()) - extractTimestamp(file1.getName())));
			}

			private long extractTimestamp(String filename) {
				int lastIndexOfDash = filename.lastIndexOf(".jar-");
				if (lastIndexOfDash == -1) {
					return 0;
				}
				try {
					return Long.parseLong(filename.substring(lastIndexOfDash + 5));
				} catch (NumberFormatException nfe1) {
					return 0;
				}
			}
		});
		return cachedFiles;
	}

	private String getFileDigest(File file, String digest) throws PluginNotFoundException {
		final int BUFFERSIZE = 4096;
		MessageDigest hash = null;
		FileInputStream fis = null;
		BufferedInputStream bis = null;
		boolean wasFromDigest256Pool = false;
		String result;

		try {
			if ("SHA-256".equals(digest)) {
				hash = SHA256.getMessageDigest(); // grab digest from pool
				wasFromDigest256Pool = true;
			} else {
				hash = MessageDigest.getInstance(digest);
			}
			// We compute the hash
			// http://java.sun.com/developer/TechTips/1998/tt0915.html#tip2
			fis = new FileInputStream(file);
			bis = new BufferedInputStream(fis);
			int len = 0;
			byte[] buffer = new byte[BUFFERSIZE];
			while((len = bis.read(buffer)) > -1) {
				hash.update(buffer, 0, len);
			}
			result = HexUtil.bytesToHex(hash.digest());
			if (wasFromDigest256Pool)
				SHA256.returnMessageDigest(hash);
		} catch(Exception e) {
			throw new PluginNotFoundException("Error while computing hash '"+digest+"' of the downloaded plugin: " + e, e);
		} finally {
			Closer.close(bis);
			Closer.close(fis);
		}
		return result;
	}

	Ticker getTicker() {
		return node.getTicker();
	}

	/**
	 * Tracks the progress of loading and starting a plugin.
	 *
	 * @author David &lsquo;Bombe&rsquo; Roden &lt;bombe@freenetproject.org&gt;
	 * @version $Id$
	 */
	public static class PluginProgress {

		enum ProgressState {
			DOWNLOADING,
			STARTING
		}

		/** The starting time. */
		private long startingTime = System.currentTimeMillis();
		/** The current state. */
		private ProgressState pluginProgress;
		/** The name by which the plugin is loaded. */
		private String name;
		/** Total. Might be bytes, might be blocks. */
		private int total;
		/** Minimum for success */
		private int minSuccessful;
		/** Current value. Same units as total. */
		private int current;
		private boolean finalisedTotal;
		private int failed;
		private int fatallyFailed;
		private final PluginDownLoader<?> loader;

		/**
		 * Creates a new progress tracker for a plugin that is loaded by the
		 * given name.
		 *
		 * @param name
		 *            The name by which the plugin is loaded
		 * @param pdl
		 */
		PluginProgress(String name, PluginDownLoader<?> pdl) {
			this.name = name;
			pluginProgress = ProgressState.DOWNLOADING;
			loader = pdl;
		}

		public void kill() {
			loader.tryCancel();
		}

		/**
		 * Returns the number of milliseconds this plugin is already being
		 * loaded.
		 *
		 * @return The time this plugin is already being loaded (in
		 *         milliseconds)
		 */
		public long getTime() {
			return System.currentTimeMillis() - startingTime;
		}

		/**
		 * Returns the name by which the plugin is loaded.
		 *
		 * @return The name by which the plugin is loaded
		 */
		public String getName() {
			return name;
		}

		/**
		 * Returns the current state of the plugin start procedure.
		 *
		 * @return The current state of the plugin
		 */
		public ProgressState getProgress() {
			return pluginProgress;
		}

		/**
		 * Sets the current state of the plugin start procedure
		 *
		 * @param pluginProgress
		 *            The current state
		 */
		void setProgress(ProgressState state) {
			this.pluginProgress = state;
		}

		/**
		 * If this object is one of the constants {@link ProgressState#DOWNLOADING} or
		 * {@link ProgressState#STARTING}, the name of those constants will be returned,
		 * otherwise a textual representation of the plugin progress is
		 * returned.
		 *
		 * @return The name of a constant, or the plugin progress
		 */
		@Override
		public String toString() {
			return "PluginProgress[name=" + name + ",startingTime=" + startingTime + ",progress=" + pluginProgress + "]";
		}

		public HTMLNode toLocalisedHTML() {
			if(pluginProgress == ProgressState.DOWNLOADING && total > 0) {
				return QueueToadlet.createProgressCell(false, true, ClientPut.COMPRESS_STATE.WORKING, current, failed, fatallyFailed, minSuccessful, total, finalisedTotal, false);
			} else if(pluginProgress == ProgressState.DOWNLOADING)
				return new HTMLNode("td", NodeL10n.getBase().getString("PproxyToadlet.startingPluginStatus.downloading"));
			else if(pluginProgress == ProgressState.STARTING)
				return new HTMLNode("td", NodeL10n.getBase().getString("PproxyToadlet.startingPluginStatus.starting"));
			else
				return new HTMLNode("td", toString());
		}

		public void setDownloadProgress(int minSuccess, int current, int total, int failed, int fatallyFailed, boolean finalised) {
			this.pluginProgress = ProgressState.DOWNLOADING;
			this.total = total;
			this.current = current;
			this.minSuccessful = minSuccess;
			this.failed = failed;
			this.fatallyFailed = fatallyFailed;
			this.finalisedTotal = finalised;
		}

		public void setDownloading() {
			this.pluginProgress = ProgressState.DOWNLOADING;
		}
		
		public boolean isOfficialPlugin() {
			return loader.isOfficialPluginLoader();
		}

		public String getLocalisedPluginName() {
			String pluginName = getName();
			if(isOfficialPlugin()) {
				return getOfficialPluginLocalisedName(pluginName);
			} else return pluginName;
		}
	}
	
	static String getOfficialPluginLocalisedName(String pluginName) {
		return l10n("pluginName."+pluginName);
	}

	public void setFProxyTheme(final THEME cssName) {
		//if (fproxyTheme.equals(cssName)) return;
		fproxyTheme = cssName;
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			pluginInfoWrapper.pr.getPageMaker().setTheme(cssName);
			if (pluginInfoWrapper.isThemedPlugin()) {
				final FredPluginThemed plug = (FredPluginThemed) pluginInfoWrapper.plug;
				executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							plug.setTheme(cssName);
						} catch (Throwable t) {
							Logger.error(this, "Cought Trowable in Callback", t);
						}
					}
				}, "Callback");
			}
		}
	}

	public static void setLanguage(LANGUAGE lang) {
		if (selfinstance == null) return;
		selfinstance.setPluginLanguage(lang);
	}

	private void setPluginLanguage(final LANGUAGE lang) {
		for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins.getLoadedPlugins()) {
			if (pluginInfoWrapper.isL10nPlugin()) {
				final FredPluginL10n plug = (FredPluginL10n) (pluginInfoWrapper.plug);
				executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							plug.setLanguage(lang);
						} catch (Throwable t) {
							Logger.error(this, "Cought Trowable in Callback", t);
						}
					}
				}, "Callback");
			} else if (pluginInfoWrapper.isBaseL10nPlugin()) {
				final FredPluginBaseL10n plug = (FredPluginBaseL10n) (pluginInfoWrapper.plug);
				executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							plug.setLanguage(lang);
						} catch (Throwable t) {
							Logger.error(this, "Cought Trowable in Callback", t);
						}
					}
				}, "Callback");
			}
		}
	}

	/**
	 * @deprecated will be removed in version 1473.
	 */
	@Deprecated
	public THEME getFProxyTheme() {
		return fproxyTheme;
	}

	public boolean loadOfficialPluginsFromWeb() {
		return alwaysLoadOfficialPluginsFromCentralServer;
	}

	public void unregisterPlugin(PluginInfoWrapper wrapper, FredPlugin plug, boolean reloading) {
		unregisterPluginToadlet(wrapper);
		if(wrapper.isConfigurablePlugin()) {
			core.getToadletContainer().unregister(wrapper.getConfigToadlet());
		}
		if(wrapper.isIPDetectorPlugin())
			node.ipDetector.unregisterIPDetectorPlugin((FredPluginIPDetector)plug);
		if(wrapper.isPortForwardPlugin())
			node.ipDetector.unregisterPortForwardPlugin((FredPluginPortForward)plug);
		if(wrapper.isBandwidthIndicator())
			node.ipDetector.unregisterBandwidthIndicatorPlugin((FredPluginBandwidthIndicator)plug);
		if(!reloading)
			node.nodeUpdater.stopPluginUpdater(wrapper.getFilename());
	}

    public boolean isEnabled() {
        return enabled;
    }

	private static class LoadedPlugins {

		private final Set<PluginProgress> startingPlugins = new HashSet<PluginProgress>();
		private final Set<PluginInfoWrapper> loadedPlugins = new HashSet<PluginInfoWrapper>();
		private final Map<String, PluginLoadFailedUserAlert> failedPluginAlerts = new HashMap<String, PluginLoadFailedUserAlert>();

		public void addStartingPlugin(PluginProgress pluginProgress) {
			synchronized (this) {
				startingPlugins.add(pluginProgress);
			}
		}

		public Collection<PluginProgress> getStartingPlugins() {
			synchronized (this) {
				return startingPlugins;
			}
		}

		public void removeStartingPlugin(PluginProgress pluginProgress) {
			synchronized (this) {
				startingPlugins.remove(pluginProgress);
			}
		}

		public Collection<PluginInfoWrapper> getLoadedPlugins() {
			synchronized (this) {
				return loadedPlugins;
			}
		}

		public void removeLoadedPlugin(PluginInfoWrapper pluginInfoWrapper) {
			synchronized (this) {
				loadedPlugins.remove(pluginInfoWrapper);
			}
		}

		public boolean hasLoadedPlugin(PluginInfoWrapper pluginInfoWrapper) {
			synchronized (this) {
				return loadedPlugins.contains(pluginInfoWrapper);
			}
		}

		public boolean hasLoadedPlugins() {
			synchronized (this) {
				return !loadedPlugins.isEmpty();
			}
		}

		public Collection<String> getFailedPluginNames() {
			synchronized (this) {
				return failedPluginAlerts.keySet();
			}
		}

		public void addLoadedPlugin(PluginInfoWrapper pluginInfoWrapper) {
			synchronized (this) {
				loadedPlugins.add(pluginInfoWrapper);
			}
		}

		public PluginLoadFailedUserAlert replaceUserAlert(String pluginName, PluginLoadFailedUserAlert pluginLoadFailedUserAlert) {
			synchronized (this) {
				return failedPluginAlerts.put(pluginName, pluginLoadFailedUserAlert);
			}
		}

		public boolean isFailedPlugin(String filename) {
			synchronized (this) {
				return failedPluginAlerts.containsKey(filename);
			}
		}

		public void removeFailedPlugin(String pluginName) {
			synchronized (this) {
				failedPluginAlerts.remove(pluginName);
			}
		}

		public boolean isKnownPlugin(String pluginName) {
			synchronized (this) {
				if (failedPluginAlerts.containsKey(pluginName)) {
					return true;
				}
				for (PluginProgress pluginProgress : startingPlugins) {
					if (pluginProgress.getName().equals(pluginName)) {
						return true;
					}
				}
				for (PluginInfoWrapper pluginInfoWrapper : loadedPlugins) {
					if (pluginInfoWrapper.getFilename().equals(pluginName)) {
						return true;
					}
				}
			}
			return false;
		}
	}

}
